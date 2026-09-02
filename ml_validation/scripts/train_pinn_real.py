"""
train_pinn_real.py
IN-GPS capstone -- SHF + PINN core-temperature estimator, REAL bench data.

Same "PINN insertion" idea as train_pinn_shf.py (small Poly(2)+Linear
regressor, gradient-descent trained in PyTorch so a physics-residual loss
term can be added) but run on the actual thermistor bench sessions
(0/25/50/75/100/150/200% motor load, see build_real_thermal_dataset.py)
instead of the synthetic FEM sweep.

Why the physics term is DIFFERENT from train_pinn_shf.py: that script's
governing equation needs a matched (T_bottom, T_top) pair straddling a
KNOWN thermal resistance (the BESIL-8230/PE-foam SHF puck) -- hardware that
doesn't exist yet. The bench rig instead has exactly what will actually be
deployed: one surface/ambient thermistor group (T_ambient, the AS6221-probe
analog) plus a room reference (T_room), with T_core only available in the
lab (5 embedded thermistors -- the ground truth this soft sensor replaces).

Physics term (lumped single-node RC model, T_ambient driving the core):

    q(t)       = G_hat * (T_ambient(t) - T_core(t))
    dT_core/dt = (q(t) - (T_core(t) - T_air) / R_loss) / C_hat

G_hat, R_loss, C_hat are learned jointly with the regressor (self-
calibrated -- real effective surface->core conductance, thermal mass and
loss path aren't independently measured here). As in train_pinn_shf.py,
the physics loss needs no T_core label, only (T_ambient, T_room) -- so it
can run on the FULL set of training runs while the data loss sees only a
small labeled fraction, which is exactly the deployment story (no core
thermistor in the field).

2026-08-26 update -- R_loss anchoring: R_loss occupies the exact same slot
in this ODE as R_motor in the steady-state calc in
docs/motor_thermal_resistance_estimation.md (both are "core-to-room loss
resistance", independent of how heat gets INTO the core). That calc used
the Labvolt 8960's datasheet-confirmed 350W rating to get a real, non-
self-calibrated value: R_motor = 0.0115 degC/W (R^2=0.96, see the doc and
fit_motor_thermal_resistance.py). Fixing R_loss to that number breaks the
3-way (G,R,C) scale degeneracy: with R known, C_hat's fitted value becomes
identifiable in real J/degC (via the loss term's 1/(R*C) coefficient), and
G_hat becomes an interpretable ambient-driven equivalent conductance. Run
main() to compare (A) data-only, (B) free-PINN, (C) anchored-PINN.

Usage: python build_real_thermal_dataset.py   (once, or after data changes)
       python train_pinn_real.py
"""

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
import matplotlib.pyplot as plt

torch.manual_seed(0)
np.random.seed(0)

DATA_PATH = "../data/real_thermal_runs.csv"
FIG_PATH = "../output/figures/15_pinn_vs_baseline_real.png"

# Held out ENTIRELY as unseen-load validation (interpolation test: 75% and
# 150% sit inside the 0-200% training range but were never trained on).
VAL_RUN_IDS = {3, 5}   # load_pct 75, 150 -- see build_real_thermal_dataset.py output
LABELED_FRACTION = 0.2   # fraction of TRAIN runs whose T_core is "available"
LAMBDA_PHYS = 0.3
N_EPOCHS = 8000
LR = 2e-2
WARMUP_FRAC = 0.3
GRAD_CLIP = 5.0

# Labvolt 8960 datasheet-anchored value (see docs/motor_thermal_resistance_estimation.md
# and fit_motor_thermal_resistance.py) -- real degC/W, not self-calibrated.
R_MOTOR_ANCHOR = 0.0115


# ---------------------------------------------------------------------------
# Data loading
# ---------------------------------------------------------------------------
def load_data():
    df = pd.read_csv(DATA_PATH)
    df = df.sort_values(["run_id", "t_s"]).reset_index(drop=True)
    return df


def split_runs(df):
    run_ids = sorted(df["run_id"].unique())
    val_runs = set(r for r in run_ids if r in VAL_RUN_IDS)
    train_runs = [r for r in run_ids if r not in VAL_RUN_IDS]

    rng = np.random.RandomState(0)
    train_runs = list(train_runs)
    rng.shuffle(train_runs)
    n_labeled = max(1, int(len(train_runs) * LABELED_FRACTION))
    labeled_runs = set(train_runs[:n_labeled])
    unlabeled_runs = set(train_runs[n_labeled:])
    return labeled_runs, unlabeled_runs, val_runs


def build_flat_tensors(df, run_ids):
    sub = df[df["run_id"].isin(run_ids)]
    return (sub["T_ambient"].values, sub["T_room"].values, sub["T_core"].values)


# ---------------------------------------------------------------------------
# Model: Poly(2) features -> single linear layer (6 params: 5 weights + bias)
# ---------------------------------------------------------------------------
class Poly2Regressor(nn.Module):
    def __init__(self, ta_mean, ta_std, tr_mean, tr_std):
        super().__init__()
        self.register_buffer("ta_mean", torch.tensor(ta_mean, dtype=torch.float32))
        self.register_buffer("ta_std", torch.tensor(ta_std, dtype=torch.float32))
        self.register_buffer("tr_mean", torch.tensor(tr_mean, dtype=torch.float32))
        self.register_buffer("tr_std", torch.tensor(tr_std, dtype=torch.float32))
        self.linear = nn.Linear(5, 1)  # [Ta, Tr, Ta^2, Ta*Tr, Tr^2] -> T_core

    def features(self, Ta, Tr):
        ta = (Ta - self.ta_mean) / self.ta_std
        tr = (Tr - self.tr_mean) / self.tr_std
        return torch.stack([ta, tr, ta**2, ta * tr, tr**2], dim=-1)

    def forward(self, Ta, Tr):
        return self.linear(self.features(Ta, Tr)).squeeze(-1)


class PhysicsParams(nn.Module):
    """Lumped constants (G_hat, R_loss, C_hat). R_loss is learnable (self-
    calibrated, scale-ambiguous with G/C) unless `fixed_r_loss` is given, in
    which case it's a real, non-trainable anchor (see R_MOTOR_ANCHOR) and
    G_hat/C_hat become identifiable in consistent units."""

    def __init__(self, fixed_r_loss: float | None = None, init_G: float = 0.5, init_C: float = 0.5):
        super().__init__()
        # softplus(x) ~= x for x >> 0, so these inits land close to init_G/init_C.
        self.raw_G = nn.Parameter(torch.tensor(float(init_G)))
        self.raw_C = nn.Parameter(torch.tensor(float(init_C)))
        if fixed_r_loss is None:
            self.raw_R = nn.Parameter(torch.tensor(0.5))
        else:
            self.register_buffer("fixed_R", torch.tensor(float(fixed_r_loss)))
            self.raw_R = None

    @property
    def G(self):
        return nn.functional.softplus(self.raw_G)

    @property
    def R_loss(self):
        if self.raw_R is None:
            return self.fixed_R
        return nn.functional.softplus(self.raw_R)

    @property
    def C_hat(self):
        return nn.functional.softplus(self.raw_C)


# ---------------------------------------------------------------------------
# Training
# ---------------------------------------------------------------------------
def train(df, labeled_runs, unlabeled_runs, val_runs, lambda_phys, label, fixed_r_loss=None,
          init_G=0.5, init_C=0.5, n_epochs=None):
    train_runs = labeled_runs | unlabeled_runs

    Ta_l, Tr_l, Tc_l = build_flat_tensors(df, labeled_runs)
    ta_mean, ta_std = Ta_l.mean(), Ta_l.std()
    tr_mean, tr_std = Tr_l.mean(), Tr_l.std()

    to_t = lambda a: torch.tensor(a, dtype=torch.float32)
    Ta_l, Tr_l, Tc_l = map(to_t, (Ta_l, Tr_l, Tc_l))

    # physics pairs: (t) and (t+1) (Ta, Tr) samples per run, for the dT_core/dt term
    sub_phys = df[df["run_id"].isin(train_runs)]
    Ta_t_list, Tr_t_list, Ta_tp1_list, Tr_tp1_list, dt_list = [], [], [], [], []
    for _, g in sub_phys.groupby("run_id"):
        g = g.sort_values("t_s")
        ta, tr, t = g["T_ambient"].values, g["T_room"].values, g["t_s"].values
        Ta_t_list.append(ta[:-1]); Tr_t_list.append(tr[:-1])
        Ta_tp1_list.append(ta[1:]); Tr_tp1_list.append(tr[1:])
        dt_list.append(np.diff(t))
    Ta_t, Tr_t, Ta_tp1, Tr_tp1, dt = map(
        lambda l: to_t(np.concatenate(l)),
        (Ta_t_list, Tr_t_list, Ta_tp1_list, Tr_tp1_list, dt_list),
    )
    # guard against zero/negative dt (duplicate timestamps in the raw logs)
    valid = dt > 1e-6
    Ta_t, Tr_t, Ta_tp1, Tr_tp1, dt = (x[valid] for x in (Ta_t, Tr_t, Ta_tp1, Tr_tp1, dt))

    n_epochs = n_epochs or N_EPOCHS
    model = Poly2Regressor(ta_mean, ta_std, tr_mean, tr_std)
    phys = PhysicsParams(fixed_r_loss=fixed_r_loss, init_G=init_G, init_C=init_C)
    params = list(model.parameters()) + (list(phys.parameters()) if lambda_phys > 0 else [])
    opt = torch.optim.Adam(params, lr=LR)

    warmup_epochs = int(n_epochs * WARMUP_FRAC)
    for epoch in range(n_epochs):
        lam = lambda_phys * min(1.0, epoch / max(1, warmup_epochs))

        opt.zero_grad()
        pred_l = model(Ta_l, Tr_l)
        loss_data = nn.functional.mse_loss(pred_l, Tc_l)

        if lambda_phys > 0:
            Tc_pred_t = model(Ta_t, Tr_t)
            Tc_pred_tp1 = model(Ta_tp1, Tr_tp1)
            dTc_dt_pred = (Tc_pred_tp1 - Tc_pred_t) / dt
            q_t = phys.G * (Ta_t - Tc_pred_t)
            dTc_dt_phys = (q_t - (Tc_pred_t - Tr_t) / phys.R_loss) / phys.C_hat
            loss_phys = nn.functional.mse_loss(dTc_dt_pred, dTc_dt_phys)
        else:
            loss_phys = torch.tensor(0.0)

        loss = loss_data + lam * loss_phys
        loss.backward()
        torch.nn.utils.clip_grad_norm_(params, GRAD_CLIP)
        opt.step()

        if epoch % 1000 == 0 or epoch == n_epochs - 1:
            msg = f"[{label}] epoch {epoch:4d}  lam={lam:.4f}  data_loss={loss_data.item():8.4f}"
            if lambda_phys > 0:
                msg += (f"  phys_loss={loss_phys.item():10.6f}"
                        f"  G_hat={phys.G.item():.4f} R_loss={phys.R_loss.item():.4f}"
                        f"  C_hat={phys.C_hat.item():.4f}")
            print(msg)

    # ---- evaluate on fully-unseen val runs (75%, 150% load -- never trained on) ----
    Ta_v, Tr_v, Tc_v = build_flat_tensors(df, val_runs)
    Ta_v, Tr_v, Tc_v = to_t(Ta_v), to_t(Tr_v), to_t(Tc_v)
    with torch.no_grad():
        pred_v = model(Ta_v, Tr_v)
    mae = (pred_v - Tc_v).abs().mean().item()
    rmse = torch.sqrt(((pred_v - Tc_v) ** 2).mean()).item()
    return model, phys, pred_v.numpy(), Tc_v.numpy(), mae, rmse


def main():
    df = load_data()
    labeled_runs, unlabeled_runs, val_runs = split_runs(df)
    print(f"runs: labeled={sorted(labeled_runs)} unlabeled(phys-only)={sorted(unlabeled_runs)} "
          f"val(unseen)={sorted(val_runs)}\n")

    print("=" * 70)
    print("(A) Data-only baseline (lambda_phys = 0, same label budget)")
    print("=" * 70)
    _, _, predA, TcA, maeA, rmseA = train(
        df, labeled_runs, unlabeled_runs, val_runs, lambda_phys=0.0, label="baseline"
    )

    print("\n" + "=" * 70)
    print(f"(B) PINN, free R_loss (lambda_phys = {LAMBDA_PHYS}, self-calibrated constants)")
    print("=" * 70)
    _, physB, predB, TcB, maeB, rmseB = train(
        df, labeled_runs, unlabeled_runs, val_runs, lambda_phys=LAMBDA_PHYS, label="pinn-free"
    )

    print("\n" + "=" * 70)
    print(f"(C) PINN, R_loss ANCHORED to R_motor={R_MOTOR_ANCHOR} degC/W (Labvolt 8960, "
          f"see docs/motor_thermal_resistance_estimation.md)")
    print("=" * 70)
    # R_loss fixed at a tiny real value (0.0115) makes (Tc-Tr)/R_loss ~1300x
    # larger than with the free/self-calibrated R~14 -- G_hat/C_hat need to
    # start near their expected real-unit scale (else Adam needs many more
    # steps to climb ~5 orders of magnitude from the old init=0.5 default;
    # see the non-convergent first attempt in conversation). init_C is the
    # midpoint of the tau/R_motor sanity range from
    # fit_motor_thermal_resistance.py; init_G=50 matches where the
    # under-tuned first attempt was still climbing toward by epoch 8000.
    _, physC, predC, TcC, maeC, rmseC = train(
        df, labeled_runs, unlabeled_runs, val_runs, lambda_phys=LAMBDA_PHYS, label="pinn-anchored",
        fixed_r_loss=R_MOTOR_ANCHOR, init_G=50.0, init_C=100000.0, n_epochs=30000,
    )

    print("\n" + "=" * 70)
    print("RESULT -- held-out (unseen 75%/150% load) validation, REAL bench data")
    print("=" * 70)
    print(f"  (A) data-only        : MAE={maeA:.3f} degC   RMSE={rmseA:.3f} degC")
    print(f"  (B) PINN (free R)    : MAE={maeB:.3f} degC   RMSE={rmseB:.3f} degC   "
          f"({100*(maeA-maeB)/maeA:+.1f}% vs A)")
    print(f"  (C) PINN (anchored R): MAE={maeC:.3f} degC   RMSE={rmseC:.3f} degC   "
          f"({100*(maeA-maeC)/maeA:+.1f}% vs A)")

    # C_hat sanity check: independent per-run exponential fits (see
    # fit_motor_thermal_resistance.py) gave tau ~ 991-1377s across runs.
    # With R fixed to R_MOTOR_ANCHOR, tau = R*C_hat implies an expected
    # C_hat range -- compare against what training actually converged to.
    c_lo, c_hi = 991.6 / R_MOTOR_ANCHOR, 1376.9 / R_MOTOR_ANCHOR
    print(f"\n  Anchored fit: G_hat={physC.G.item():.5f}  R_loss={physC.R_loss.item():.4f} (fixed)  "
          f"C_hat={physC.C_hat.item():.1f}")
    print(f"  Sanity range for C_hat from independent tau/R_motor: [{c_lo:.0f}, {c_hi:.0f}]  "
          f"(tau=991-1377s from fit_motor_thermal_resistance.py, C=tau/R)")
    in_range = c_lo <= physC.C_hat.item() <= c_hi
    print(f"  C_hat {'FALLS INSIDE' if in_range else 'falls OUTSIDE'} the independently-derived range")

    fig, axes = plt.subplots(1, 3, figsize=(16, 5))
    for ax, pred, Tc, name, mae in [
        (axes[0], predA, TcA, "Data-only baseline", maeA),
        (axes[1], predB, TcB, "PINN (free R_loss)", maeB),
        (axes[2], predC, TcC, "PINN (R_loss anchored)", maeC),
    ]:
        lo, hi = min(Tc.min(), pred.min()) - 2, max(Tc.max(), pred.max()) + 2
        ax.scatter(Tc, pred, alpha=0.15, s=8)
        ax.plot([lo, hi], [lo, hi], "r--", linewidth=1.3)
        ax.set_xlabel("T_core (true, unseen 75%/150% load)")
        ax.set_ylabel("T_core (pred)")
        ax.set_title(f"{name}\nMAE={mae:.3f} degC")
    plt.tight_layout()
    plt.savefig(FIG_PATH, dpi=130)
    print(f"\nSaved comparison plot to {FIG_PATH}")


if __name__ == "__main__":
    main()
