"""
shf_fem_simulate.py
IN-GPS capstone -- SHF(Single Heat Flux) sensor FEM(FD) re-simulation.

Reproduces the geometry of Kim et al., "Deep-learning-based approach for
accurate and rapid estimation of core body temperature using a single heat
flux sensor," Results in Engineering 32 (2026) 112236, but swaps the
"skin" boundary condition for an IN-GPS equipment target:

  - Same probe geometry as the paper's FEM model (Fig. 1A): concentric
    PDMS core (10 mm dia) inside a PE-foam shell (30 mm dia, 5 mm tall),
    plus a thin 0.5 mm PE cap on top (paper Sec. 3.3 fabrication note).
  - The "skin" layer (paper: fixed 5 mm, kg = 0.32-0.50 W/m*K) is
    reinterpreted as an EFFECTIVE conduction path between the equipment's
    true hotspot and the housing surface the probe sits on. Because the
    housing is metal (near-isothermal, k in the tens-hundreds W/m*K), the
    real unknown thermal resistance lives *inside* the equipment (air
    gap / insulation / contact resistance) -- so kg is swept over a wide
    range (0.1-100 W/m*K) instead of pinned to bulk metal conductivity.
    See conversation notes: at kg >> ks the SHF correction term
    (ks/kg)*(Tbottom-Ttop) collapses to ~0, which would make the method
    pointless -- the 260316_..._200_load.csv bench data shows large
    inter-channel spread (17-100C at the same instant), which is
    evidence a real internal gradient/resistance exists, so kg is left
    as an unknown to be calibrated rather than assumed.

Method: 2D axisymmetric transient heat conduction, finite-volume
discretization in (r, z), implicit time stepping via
scipy.integrate.solve_ivp (BDF, sparse Jacobian == the conductance
matrix itself since the problem is linear).

Output: one row per (T_core, T_air, h, kg, t) sample, columns
[run_id, T_core, T_air, h, kg, t_s, T_top, T_bottom], written to
../data/shf_fem_synthetic.csv -- meant to be consumed the same way the
existing Thermistor_*/electrical_data_*.csv files feed
01_model_evaluation.ipynb.

All material property values not fixed by conversation (rho, c for each
region; ks, k_pe) are literature-typical placeholders, flagged inline --
replace with measured values once the physical PDMS/PE stock is
characterized.
"""

import itertools
import time

import numpy as np
import pandas as pd
from scipy import sparse
from scipy.integrate import solve_ivp

# ---------------------------------------------------------------------------
# Geometry (paper Fig. 1A + Sec. 3.3 fabrication note; "same as paper" per
# 2026-08-25 conversation decision)
# ---------------------------------------------------------------------------
R_CORE = 5.0e-3      # PDMS core radius (10 mm dia)              [m]
R_OUT = 15.0e-3       # outer PE foam radius (30 mm dia)          [m]
L_TISSUE = 5.0e-3    # equipment-side "hidden path" layer height [m]
L_CORE = 4.5e-3      # PDMS core height                          [m]
L_CAP = 0.5e-3       # top PE foam cap height                    [m]
Z_TOP = L_TISSUE + L_CORE + L_CAP

# ---------------------------------------------------------------------------
# Material properties -- back to the paper's PDMS core (2026-08-26 decision):
# swapping in BESIL-8230 (k=0.8 W/m*K, datasheet-confirmed) collapsed the SHF
# signal to noise on the full kg=0.1-100 sweep (MAE~6.1 degC, physics loss
# gave +0.0% -- see conversation/output/figures/14_pinn_vs_baseline.png from
# that run). PDMS's lower conductivity keeps ks << most of the swept kg
# range, which is what preserves (T_bottom-T_top) as an informative signal.
# BESIL-8230 remains a real, characterized option (see docs/ and
# shf_core_model.h's export path) if a future geometry/kg-range revisit
# shows it works once the real equipment's kg range is known and narrower.
#   ks    : PDMS (Sylgard 184 cured)            ~0.15-0.20 W/m*K  (lit. typical)
#   k_pe  : generic PE foam (no specific product picked yet)  ~0.03-0.04 W/m*K
#   kg    : SWEPT -- effective equipment-side conductivity (0.1-100 W/m*K)
# rho*c triplets are generic-solid / generic-foam placeholders; they set the
# *speed* of the transient response, which matters for the 30 s window
# metric but not for the steady-state Eq.(2) baseline. Replace once real
# PDMS/PE/equipment-path thermal mass is known.
# ---------------------------------------------------------------------------
K_S = 0.18            # PDMS conductivity      [W/m/K]
K_PE = 0.035           # PE foam conductivity    [W/m/K]

RHOC_TISSUE = 1200.0 * 1500.0   # placeholder generic solid   [J/m^3/K]
RHOC_PDMS = 970.0 * 1460.0      # Sylgard 184 typical         [J/m^3/K]
RHOC_PE = 30.0 * 1700.0         # closed-cell PE foam typical [J/m^3/K]

# ---------------------------------------------------------------------------
# Parameter sweep (2026-08-25 conversation decision)
# ---------------------------------------------------------------------------
T_CORE_LIST = [20.0, 40.0, 60.0, 80.0, 100.0]         # degC, 0-200% load range
T_AIR_LIST = [5.0, 20.0, 35.0]                        # degC, paper range
H_LIST = [0.0, 10.0, 25.0, 50.0]                      # W/m^2/K, paper range
KG_LIST = [0.1, 0.3, 1.0, 3.0, 10.0, 30.0, 100.0]     # W/m/K, log-swept unknown

T_SIM_S = 1200.0     # total simulated time [s] (matches paper's transient window)
DT_SAVE = 1.0        # sample every 1 s (matches paper's 1 Hz thermistor logging)

# Grid resolution
NR = 26
NZ = 26


def build_grid():
    r = np.linspace(0.0, R_OUT, NR)
    z = np.linspace(0.0, Z_TOP, NZ)
    dr = r[1] - r[0]
    dz = z[1] - z[0]
    return r, z, dr, dz


def material_k(r_i, z_j, kg):
    """Conductivity at node (r_i, z_j) for the given kg (tissue layer)."""
    if z_j < L_TISSUE - 1e-12:
        return kg
    if z_j < L_TISSUE + L_CORE - 1e-12:
        return K_S if r_i <= R_CORE + 1e-12 else K_PE
    return K_PE  # top cap, all r


def material_rhoc(r_i, z_j, kg):
    if z_j < L_TISSUE - 1e-12:
        # scale a generic solid's rho*c with kg is not physical; keep fixed
        return RHOC_TISSUE
    if z_j < L_TISSUE + L_CORE - 1e-12:
        return RHOC_PDMS if r_i <= R_CORE + 1e-12 else RHOC_PE
    return RHOC_PE


def harmonic_mean(a, b):
    if a <= 0 or b <= 0:
        return 0.0
    return 2.0 * a * b / (a + b)


def assemble(kg, h):
    """
    Build the conductance matrix K (Nr*Nz x Nr*Nz), the diagonal thermal-
    mass matrix C, and boundary bookkeeping for a given (kg, h).

    Node (i, j) -> flat index idx = j*NR + i.
    z=0 row (j=0) is a Dirichlet boundary (T_core) and is EXCLUDED from the
    unknown vector; its influence enters the RHS forcing term via the
    conductance to row j=1.
    """
    r, z, dr, dz = build_grid()

    def idx(i, j):
        return j * NR + i

    n_unknown = NR * (NZ - 1)  # rows j=1..NZ-1

    def uidx(i, j):
        # map (i, j>=1) -> position in the unknown vector
        return (j - 1) * NR + i

    rows, cols, vals = [], [], []
    C_diag = np.zeros(n_unknown)
    b_dirichlet = np.zeros(n_unknown)  # forcing from the fixed T_core row

    # face radius for r-direction conductance, valid for i=0..NR-2 (face between i,i+1)
    r_face = r[:-1] + dr / 2.0

    # cross-sectional "area" per node (2*pi dropped, consistent throughout)
    area_z = np.empty(NR)
    area_z[0] = dr**2 / 8.0
    area_z[1:] = r[1:] * dr

    for j in range(1, NZ):
        for i in range(NR):
            u = uidx(i, j)
            k_ij = material_k(r[i], z[j], kg)
            rc_ij = material_rhoc(r[i], z[j], kg)
            C_diag[u] = rc_ij * area_z[i] * dz

            g_sum = 0.0

            # --- radial neighbors ---
            if i > 0:
                k_w = material_k(r[i - 1], z[j], kg)
                k_face = harmonic_mean(k_ij, k_w)
                g = k_face * (r_face[i - 1] * dz) / dr
                rows.append(u); cols.append(uidx(i - 1, j)); vals.append(-g)
                g_sum += g
            if i < NR - 1:
                k_e = material_k(r[i + 1], z[j], kg)
                k_face = harmonic_mean(k_ij, k_e)
                g = k_face * (r_face[i] * dz) / dr
                rows.append(u); cols.append(uidx(i + 1, j)); vals.append(-g)
                g_sum += g
            # r = R_OUT outer boundary: adiabatic -> no extra term

            # --- axial neighbors ---
            if j > 1:
                k_s = material_k(r[i], z[j - 1], kg)
                k_face = harmonic_mean(k_ij, k_s)
                g = k_face * area_z[i] / dz
                rows.append(u); cols.append(uidx(i, j - 1)); vals.append(-g)
                g_sum += g
            else:
                # j == 1: neighbor below is the Dirichlet T_core row (j=0)
                k_s = material_k(r[i], 0.0, kg)
                k_face = harmonic_mean(k_ij, k_s)
                g = k_face * area_z[i] / dz
                b_dirichlet[u] += g          # * T_core, added at solve time
                g_sum += g

            if j < NZ - 1:
                k_n = material_k(r[i], z[j + 1], kg)
                k_face = harmonic_mean(k_ij, k_n)
                g = k_face * area_z[i] / dz
                rows.append(u); cols.append(uidx(i, j + 1)); vals.append(-g)
                g_sum += g
            else:
                # j == NZ-1: top surface, convective (Robin) BC
                # flux out = h * A * (T - T_air) ; A == area_z[i] (top face)
                g = h * area_z[i]
                g_sum += g
                # contributes h*A*T_air to RHS forcing (like Dirichlet term)
                b_dirichlet[u] += 0.0  # placeholder, T_air handled at solve time
                # store separately since it multiplies T_air, not T_core:
                rows.append(u); cols.append(-1); vals.append(g)  # sentinel row, filtered below

            rows.append(u); cols.append(u); vals.append(g_sum)

    # separate the convective-BC sentinel entries (col == -1) into their own vector
    rows = np.array(rows); cols = np.array(cols); vals = np.array(vals)
    conv_mask = cols == -1
    conv_rows = rows[conv_mask]
    conv_vals = vals[conv_mask]
    b_air_coeff = np.zeros(n_unknown)
    b_air_coeff[conv_rows] = conv_vals

    keep = ~conv_mask
    K = sparse.csr_matrix((vals[keep], (rows[keep], cols[keep])), shape=(n_unknown, n_unknown))

    return K, C_diag, b_dirichlet, b_air_coeff, r, z


def bottom_top_indices(r, z):
    """Flat (unknown-vector) index of the r=0 bottom/top thermistor nodes."""
    i0 = 0  # r = 0
    j_bottom = int(round(L_TISSUE / (z[1] - z[0])))       # tissue/PDMS interface
    j_top = NZ - 1                                        # outer top surface
    # unknown-vector indexing excludes j=0, so:
    u_bottom = (j_bottom - 1) * NR + i0
    u_top = (j_top - 1) * NR + i0
    return u_bottom, u_top


def run_case(t_core, t_air, h, kg, k_mat, c_diag, b_dir, b_air, r, z):
    n = c_diag.shape[0]
    Cinv = 1.0 / c_diag
    forcing = b_dir * t_core + b_air * t_air

    def rhs(t, T):
        return Cinv * (-(k_mat @ T) + forcing)

    def jac(t, T):
        return sparse.diags(Cinv) @ (-k_mat)

    T0 = np.full(n, t_air)  # cold-start: whole probe at ambient
    t_eval = np.arange(0.0, T_SIM_S + DT_SAVE, DT_SAVE)

    sol = solve_ivp(rhs, (0.0, T_SIM_S), T0, method="BDF", jac=jac,
                     t_eval=t_eval, rtol=1e-6, atol=1e-8)

    u_bottom, u_top = bottom_top_indices(r, z)
    return sol.t, sol.y[u_bottom, :], sol.y[u_top, :]


def main():
    records = []
    run_id = 0
    combos = list(itertools.product(KG_LIST, H_LIST))
    t_start = time.time()
    for kg, h in combos:
        K, c_diag, b_dir, b_air, r, z = assemble(kg, h)
        for t_air in T_AIR_LIST:
            for t_core in T_CORE_LIST:
                if t_core <= t_air:
                    continue  # no meaningful heating case
                t, T_bottom, T_top = run_case(
                    t_core, t_air, h, kg, K, c_diag, b_dir, b_air, r, z
                )
                for tt, tb, tp in zip(t, T_bottom, T_top):
                    records.append((run_id, t_core, t_air, h, kg, tt, tp, tb))
                run_id += 1
        print(f"kg={kg:6.2f} h={h:5.1f}  done  ({run_id} runs so far, "
              f"{time.time()-t_start:6.1f}s elapsed)")

    df = pd.DataFrame(records, columns=[
        "run_id", "T_core", "T_air", "h", "kg", "t_s", "T_top", "T_bottom"
    ])
    out_path = "../data/shf_fem_synthetic.csv"
    df.to_csv(out_path, index=False)
    print(f"\nWrote {len(df)} rows ({run_id} runs) to {out_path}")
    print(df.groupby("run_id").size().describe())


if __name__ == "__main__":
    main()
