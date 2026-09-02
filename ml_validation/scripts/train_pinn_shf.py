"""
train_pinn_shf.py
IN-GPS capstone -- SHF + PINN core-temperature estimator.

Trains a small Poly(2)+Linear regressor -- same functional FORM and
parameter count as the Poly(2)+LR model already selected as a deployment
candidate in 01_model_evaluation.ipynb (5 poly features + intercept = 6
weights) -- to predict T_core from (T_bottom, T_top). Unlike sklearn's
closed-form LinearRegression, this is trained with gradient descent in
PyTorch so a physics-residual loss term can be added: this IS the "PINN
insertion" discussed in conversation -- same small architecture, extra
loss term, not a bigger network.

Physics term (lumped single-heat-flux ODE, matches the FEM script's
governing assumption):

    q(t)       = kg_hat * (T_bottom(t) - T_top(t)) / L_TISSUE
    dT_core/dt = (q(t) - (T_core(t) - T_air) / R_loss) / C_hat

kg_hat, R_loss, C_hat are learned jointly with the regressor (reference-
free calibration -- we don't know the real equipment's effective
conductivity, so the network fits it from the transient shape instead of
assuming a value). Units are self-consistent/effective, not literal SI --
the FEM tissue-layer thermal mass was itself a placeholder (see
shf_fem_simulate.py docstring), so treat kg_hat/R_loss/C_hat as fitted
lumped constants, not measured material properties.

Key property exploited here: the physics loss needs NO T_core label (only
T_bottom, T_top, T_air), so it can be computed on runs that have no ground
truth. This script demonstrates that value directly -- it trains on a
SMALL LABELED FRACTION of the FEM runs (data loss) but the FULL set of
training runs for the physics loss, then compares:

  (A) data-only baseline  -- same architecture, lambda_phys = 0
  (B) PINN                -- same architecture, lambda_phys > 0

on a held-out set of ENTIRELY UNSEEN (kg, h, T_core) combinations. If (B)
generalizes better than (A) with identical label budget, that's the
concrete case for the physics term.

Usage: python train_pinn_shf.py
"""

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
import matplotlib.pyplot as plt

torch.manual_seed(0)
np.random.seed(0)

DATA_PATH = "../data/shf_fem_synthetic.csv"
FIG_PATH = "../output/figures/14_pinn_vs_baseline.png"
L_TISSUE = 5.0e-3  # must match shf_fem_simulate.py

LABELED_FRACTION = 0.2   # fraction of TRAIN runs whose T_core is "available"
VAL_FRACTION = 0.2       # fraction of ALL runs held out entirely (unseen combos)
LAMBDA_PHYS = 0.3
N_EPOCHS = 8000
LR = 2e-2
WARMUP_FRAC = 0.3        # ramp lambda_phys 0 -> target over the first 30% of epochs
GRAD_CLIP = 5.0


# ---------------------------------------------------------------------------
# Data loading
# ---------------------------------------------------------------------------
def load_data():
    df = pd.read_csv(DATA_PATH)
    df = df.sort_values(["run_id", "t_s"]).reset_index(drop=True)
    return df


def split_runs(df):
    run_meta = df.drop_duplicates("run_id")[["run_id", "T_core", "T_air", "h", "kg"]]
    run_ids = run_meta["run_id"].values.copy()
    rng = np.random.RandomState(0)
    rng.shuffle(run_ids)

    n_val = int(len(run_ids) * VAL_FRACTION)
    val_runs = set(run_ids[:n_val])
    train_runs = run_ids[n_val:]

    n_labeled = max(1, int(len(train_runs) * LABELED_FRACTION))
    labeled_runs = set(train_runs[:n_labeled])
    unlabeled_runs = set(train_runs[n_labeled:])

    return labeled_runs, unlabeled_runs, val_runs


def build_flat_tensors(df, run_ids):
    sub = df[df["run_id"].isin(run_ids)]
    return (sub["T_bottom"].values, sub["T_top"].values,
            sub["T_air"].values, sub["T_core"].values)


# ---------------------------------------------------------------------------
# Model: Poly(2) features -> single linear layer (6 params: 5 weights + bias)
# ---------------------------------------------------------------------------
class Poly2Regressor(nn.Module):
    def __init__(self, tb_mean, tb_std, tt_mean, tt_std):
        super().__init__()
        self.register_buffer("tb_mean", torch.tensor(tb_mean, dtype=torch.float32))
        self.register_buffer("tb_std", torch.tensor(tb_std, dtype=torch.float32))
        self.register_buffer("tt_mean", torch.tensor(tt_mean, dtype=torch.float32))
        self.register_buffer("tt_std", torch.tensor(tt_std, dtype=torch.float32))
        self.linear = nn.Linear(5, 1)  # [Tb, Tt, Tb^2, Tb*Tt, Tt^2] -> T_core

    def features(self, Tb, Tt):
        tb = (Tb - self.tb_mean) / self.tb_std
        tt = (Tt - self.tt_mean) / self.tt_std
        return torch.stack([tb, tt, tb**2, tb * tt, tt**2], dim=-1)

    def forward(self, Tb, Tt):
        return self.linear(self.features(Tb, Tt)).squeeze(-1)


class PhysicsParams(nn.Module):
    """Learnable, self-calibrated lumped constants (kg_hat, R_loss, C_hat)."""

    def __init__(self):
        super().__init__()
        self.raw_kg = nn.Parameter(torch.tensor(0.5))
        self.raw_R = nn.Parameter(torch.tensor(0.5))
        self.raw_C = nn.Parameter(torch.tensor(0.5))

    @property
    def kg(self):
        return nn.functional.softplus(self.raw_kg)

    @property
    def R_loss(self):
        return nn.functional.softplus(self.raw_R)

    @property
    def C_hat(self):
        return nn.functional.softplus(self.raw_C)


# ---------------------------------------------------------------------------
# Training
# ---------------------------------------------------------------------------
def train(df, labeled_runs, unlabeled_runs, val_runs, lambda_phys, label):
    train_runs = labeled_runs | unlabeled_runs

    Tb_l, Tt_l, Ta_l, Tc_l = build_flat_tensors(df, labeled_runs)
    tb_mean, tb_std = Tb_l.mean(), Tb_l.std()
    tt_mean, tt_std = Tt_l.mean(), Tt_l.std()

    to_t = lambda a: torch.tensor(a, dtype=torch.float32)
    Tb_l, Tt_l, Ta_l, Tc_l = map(to_t, (Tb_l, Tt_l, Ta_l, Tc_l))

    # physics pairs: (t) side values + dt, and (t+1) side (Tb,Tt) for the second prediction
    sub_phys = df[df["run_id"].isin(train_runs)]
    Tb_t_list, Tt_t_list, Ta_t_list, Tb_tp1_list, Tt_tp1_list, dt_list = [], [], [], [], [], []
    for _, g in sub_phys.groupby("run_id"):
        g = g.sort_values("t_s")
        tb, tt, ta, t = g["T_bottom"].values, g["T_top"].values, g["T_air"].values, g["t_s"].values
        Tb_t_list.append(tb[:-1]); Tt_t_list.append(tt[:-1]); Ta_t_list.append(ta[:-1])
        Tb_tp1_list.append(tb[1:]); Tt_tp1_list.append(tt[1:])
        dt_list.append(np.diff(t))
    Tb_t, Tt_t, Ta_t, Tb_tp1, Tt_tp1, dt = map(
        lambda l: to_t(np.concatenate(l)),
        (Tb_t_list, Tt_t_list, Ta_t_list, Tb_tp1_list, Tt_tp1_list, dt_list),
    )

    model = Poly2Regressor(tb_mean, tb_std, tt_mean, tt_std)
    phys = PhysicsParams()
    params = list(model.parameters()) + (list(phys.parameters()) if lambda_phys > 0 else [])
    opt = torch.optim.Adam(params, lr=LR)

    warmup_epochs = int(N_EPOCHS * WARMUP_FRAC)
    for epoch in range(N_EPOCHS):
        lam = lambda_phys * min(1.0, epoch / max(1, warmup_epochs))

        opt.zero_grad()
        pred_l = model(Tb_l, Tt_l)
        loss_data = nn.functional.mse_loss(pred_l, Tc_l)

        if lambda_phys > 0:
            Tc_pred_t = model(Tb_t, Tt_t)
            Tc_pred_tp1 = model(Tb_tp1, Tt_tp1)
            dTc_dt_pred = (Tc_pred_tp1 - Tc_pred_t) / dt
            q_t = phys.kg * (Tb_t - Tt_t) / L_TISSUE
            dTc_dt_phys = (q_t - (Tc_pred_t - Ta_t) / phys.R_loss) / phys.C_hat
            loss_phys = nn.functional.mse_loss(dTc_dt_pred, dTc_dt_phys)
        else:
            loss_phys = torch.tensor(0.0)

        loss = loss_data + lam * loss_phys
        loss.backward()
        torch.nn.utils.clip_grad_norm_(params, GRAD_CLIP)
        opt.step()

        if epoch % 1000 == 0 or epoch == N_EPOCHS - 1:
            msg = f"[{label}] epoch {epoch:4d}  lam={lam:.4f}  data_loss={loss_data.item():8.4f}"
            if lambda_phys > 0:
                msg += (f"  phys_loss={loss_phys.item():10.6f}"
                        f"  kg_hat={phys.kg.item():.4f} R_loss={phys.R_loss.item():.4f}"
                        f"  C_hat={phys.C_hat.item():.4f}")
            print(msg)

    # ---- evaluate on fully-unseen val runs ----
    Tb_v, Tt_v, Ta_v, Tc_v = build_flat_tensors(df, val_runs)
    Tb_v, Tt_v, Tc_v = to_t(Tb_v), to_t(Tt_v), to_t(Tc_v)
    with torch.no_grad():
        pred_v = model(Tb_v, Tt_v)
    mae = (pred_v - Tc_v).abs().mean().item()
    rmse = torch.sqrt(((pred_v - Tc_v) ** 2).mean()).item()
    return model, phys, pred_v.numpy(), Tc_v.numpy(), mae, rmse


def main():
    df = load_data()
    labeled_runs, unlabeled_runs, val_runs = split_runs(df)
    print(f"runs: labeled={len(labeled_runs)} unlabeled(phys-only)={len(unlabeled_runs)} "
          f"val(unseen)={len(val_runs)}\n")

    print("=" * 70)
    print("(A) Data-only baseline (lambda_phys = 0, same label budget)")
    print("=" * 70)
    _, _, predA, TcA, maeA, rmseA = train(
        df, labeled_runs, unlabeled_runs, val_runs, lambda_phys=0.0, label="baseline"
    )

    print("\n" + "=" * 70)
    print(f"(B) PINN (lambda_phys = {LAMBDA_PHYS}, same label budget + physics on unlabeled runs)")
    print("=" * 70)
    _, physB, predB, TcB, maeB, rmseB = train(
        df, labeled_runs, unlabeled_runs, val_runs, lambda_phys=LAMBDA_PHYS, label="pinn"
    )

    print("\n" + "=" * 70)
    print("RESULT -- held-out (unseen kg/h/T_core) validation")
    print("=" * 70)
    print(f"  (A) data-only : MAE={maeA:.3f} degC   RMSE={rmseA:.3f} degC")
    print(f"  (B) PINN      : MAE={maeB:.3f} degC   RMSE={rmseB:.3f} degC")
    improvement = 100 * (maeA - maeB) / maeA
    print(f"  MAE change from adding physics loss: {improvement:+.1f}%")

    fig, axes = plt.subplots(1, 2, figsize=(11, 5))
    for ax, pred, Tc, name, mae in [
        (axes[0], predA, TcA, "Data-only baseline", maeA),
        (axes[1], predB, TcB, "PINN", maeB),
    ]:
        lo, hi = min(Tc.min(), pred.min()) - 2, max(Tc.max(), pred.max()) + 2
        ax.scatter(Tc, pred, alpha=0.15, s=8)
        ax.plot([lo, hi], [lo, hi], "r--", linewidth=1.3)
        ax.set_xlabel("T_core (true, unseen combos)")
        ax.set_ylabel("T_core (pred)")
        ax.set_title(f"{name}\nMAE={mae:.3f} degC")
    plt.tight_layout()
    plt.savefig(FIG_PATH, dpi=130)
    print(f"\nSaved comparison plot to {FIG_PATH}")


if __name__ == "__main__":
    main()
