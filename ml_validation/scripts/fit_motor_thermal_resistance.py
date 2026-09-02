"""
fit_motor_thermal_resistance.py
IN-GPS capstone -- estimate the Labvolt 8960 dynamometer's real thermal
resistance R_motor [degC/W] from the bench thermistor runs, anchored by the
unit's datasheet-confirmed nominal power (2026-08-26 conversation).

Method (steady-state, no ML needed -- see docs/motor_thermal_resistance_estimation.md):
  1. Per run, extrapolate the TRUE steady-state core temperature T_inf via a
     single-exponential fit to the whole T_core(t) trajectory:
         T_core(t) = T_inf - (T_inf - T0) * exp(-t/tau)
     This recovers T_inf even for runs that were logged too briefly to
     visibly flatten out (notably the 200% overload run).
  2. Convert load_pct -> applied power P using the Labvolt 8960 series
     nominal rating: P_RATED = 350 W (dynamometer mode). Source: Lab-Volt
     Model 8960 Four-Quadrant Dynamometer/Power Supply datasheet (same
     rating across the 8960-A..F / regional-suffix variants, incl. 8960-10 --
     https://www.yumpu.com/en/document/view/12216629/... and
     https://labvolt.festo.com/solutions/6_power_energy/98-8960-00_...).
  3. Fit dT_inf = a + R_motor * P (free intercept, NOT forced through the
     origin). The intercept `a` is real and expected: per the 2026-08-26
     conversation, the dynamometer spins at rated speed even at 0% torque
     load, so windage/bearing friction/iron losses produce baseline heating
     independent of the electrical load. R_motor is the INCREMENTAL thermal
     resistance -- how much extra core-room delta-T each extra watt of
     torque-load power buys.

200% overload run (run_id=6) is EXCLUDED from the fit: its logged duration
(560 s) is far shorter than the ~1000-1400 s time constants seen in every
other run, so its extrapolated T_inf is unreliable (large curve_fit stderr,
see 02_pinn_shf_real_data.ipynb). Re-include once a longer 200% capture
exists.

Usage: python fit_motor_thermal_resistance.py
Output: ../output/figures/16_r_motor_fit.png, prints the fit table + R_motor.
"""

import numpy as np
import pandas as pd
from scipy.optimize import curve_fit
import matplotlib.pyplot as plt

DATA_PATH = "../data/real_thermal_runs.csv"
FIG_PATH = "../output/figures/16_r_motor_fit.png"

P_RATED_W = 350.0          # Labvolt 8960 series nominal power (dynamometer mode), datasheet
EXCLUDE_RUN_IDS = {6}      # 200% overload: run too short (560s) vs tau~1000-1400s elsewhere


def exp_rise(t, T_inf, T0, tau):
    return T_inf - (T_inf - T0) * np.exp(-t / tau)


def fit_run_asymptote(g: pd.DataFrame):
    """Single-exponential fit of T_core(t) -> (T_inf, T0, tau, stderr(T_inf))."""
    t = g["t_s"].values
    Tc = g["T_core"].values
    T0_guess = Tc[0]
    Tinf_guess = Tc[-1] + (Tc[-1] - Tc[len(Tc) // 2])
    popt, pcov = curve_fit(exp_rise, t, Tc, p0=[Tinf_guess, T0_guess, 300.0], maxfev=20000)
    perr = np.sqrt(np.diag(pcov))
    return popt[0], popt[1], popt[2], perr[0]


def main():
    df = pd.read_csv(DATA_PATH)

    rows = []
    for rid, g in df.groupby("run_id"):
        g = g.sort_values("t_s")
        load = g["load_pct"].iloc[0]
        T_inf, T0, tau, T_inf_stderr = fit_run_asymptote(g)
        dT_inf = T_inf - g["T_room"].mean()
        rows.append((rid, load, g["t_s"].max(), T_inf, tau, T_inf_stderr, dT_inf))

    fit_df = pd.DataFrame(rows, columns=[
        "run_id", "load_pct", "dur_s", "T_inf", "tau_s", "T_inf_stderr", "dT_inf",
    ])
    fit_df["P_W"] = P_RATED_W * fit_df["load_pct"] / 100.0
    fit_df["excluded"] = fit_df["run_id"].isin(EXCLUDE_RUN_IDS)

    print("Per-run extrapolated steady state:")
    print(fit_df.to_string(index=False))

    used = fit_df[~fit_df["excluded"]]
    A = np.vstack([np.ones(len(used)), used["P_W"].values]).T
    (a, R_motor), *_ = np.linalg.lstsq(A, used["dT_inf"].values, rcond=None)
    pred = a + R_motor * used["P_W"].values
    resid = used["dT_inf"].values - pred
    r2 = 1 - np.sum(resid ** 2) / np.sum((used["dT_inf"].values - used["dT_inf"].mean()) ** 2)

    print(f"\nFit (excluding run_id {sorted(EXCLUDE_RUN_IDS)}): "
          f"dT_inf = {a:.3f} + {R_motor:.5f} * P")
    print(f"  R_motor (incremental)      = {R_motor:.4f} degC/W")
    print(f"  baseline (rated-speed, 0-torque windage/bearing/iron loss) = {a:.3f} degC")
    print(f"  R^2 = {r2:.4f}")
    print(f"\n  P_RATED source: Labvolt 8960 series datasheet, 350W nominal "
          f"(dynamometer mode) -- see file docstring for links.")

    fig, ax = plt.subplots(figsize=(7, 5.5))
    ax.scatter(used["P_W"], used["dT_inf"], s=50, zorder=3, label="used in fit")
    excl = fit_df[fit_df["excluded"]]
    if len(excl):
        ax.scatter(excl["P_W"], excl["dT_inf"], s=50, marker="x", color="red", zorder=3,
                   label="excluded (200% -- run too short, T_inf unreliable)")
    p_line = np.linspace(0, max(fit_df["P_W"].max(), 1), 50)
    ax.plot(p_line, a + R_motor * p_line, "r--", linewidth=1.3,
            label=f"fit: dT={a:.2f}+{R_motor:.4f}*P (R2={r2:.3f})")
    for _, r in fit_df.iterrows():
        ax.annotate(f"{r['load_pct']:.0f}%", (r["P_W"], r["dT_inf"]),
                    textcoords="offset points", xytext=(6, 4), fontsize=9)
    ax.set_xlabel("Applied power P [W]  (load% x Labvolt 8960 nominal 350W)")
    ax.set_ylabel("Extrapolated steady-state dT [degC]  (T_core_inf - T_room)")
    ax.set_title("Labvolt 8960 motor -- thermal resistance fit\n"
                  f"R_motor = {R_motor:.4f} degC/W  (baseline {a:.2f} degC from rated-speed idling)")
    ax.legend(fontsize=8)
    plt.tight_layout()
    plt.savefig(FIG_PATH, dpi=130)
    print(f"\nSaved plot to {FIG_PATH}")

    return fit_df, a, R_motor, r2


if __name__ == "__main__":
    main()
