"""
build_real_thermal_dataset.py
IN-GPS capstone -- flattens the real bench thermistor sessions (already used
by notebooks/01_model_evaluation.ipynb) into the same long format
(run_id, t_s, T_core, T_ambient, T_room, load_pct) as shf_fem_synthetic.csv,
so train_pinn_real.py can reuse the FEM/PINN script's train/val-split and
comparison machinery on REAL data instead of the synthetic FEM sweep.

This does not re-run the notebook -- it re-implements just the loading path
(load_thermistor_session, run grouping by 60-min gap, LOAD_MAPPING, the
200%-overload special file) from 01_model_evaluation.ipynb Section 1-2,
dropping the vibration merge (not needed for the T_ambient/T_room -> T_core
lumped model). Column definitions (which channels average into T_core /
T_ambient / T_room) are copied verbatim from that notebook -- if the notebook
loader changes, update here too.

Output: ../data/real_thermal_runs.csv
"""

import glob
import re

import numpy as np
import pandas as pd

DATA_DIR = "../data"
OUT_PATH = "../data/real_thermal_runs.csv"
RUN_GAP_MIN = 60

LOAD_MAPPING = {
    "26_05_04": 0,
    "05_08": 25,
    "05_12": 50,
    "05_10": 75,
    "05_09": 100,
    "05_13": 150,
    "26_03_16": 200,
}


def parse_dt(series):
    dt = pd.to_datetime(series, format="%Y_%m_%d_%H_%M_%S.%f", errors="coerce")
    if dt.isna().any():
        dt2 = pd.to_datetime(series, format="%Y_%m_%d_%H_%M_%S", errors="coerce")
        dt = dt.fillna(dt2)
    return dt


def date_prefix_for_token(tok: str) -> str:
    if tok.startswith("26_"):
        return tok  # "26_05_04" already a full key
    return "_".join(tok.split("_")[:2])  # "05_08_09_07" -> "05_08"


def load_thermistor_session(token: str) -> pd.DataFrame:
    """One sub-session -> mean(5 core ch) + mean(5 ambient ch) + room. Same
    channel wiring as 01_model_evaluation.ipynb load_thermistor_session()."""
    abcd = pd.read_csv(f"{DATA_DIR}/Thermistor_ABCD_{token}.csv")
    efgh = pd.read_csv(f"{DATA_DIR}/Thermistor_EFGH_{token}.csv")
    ij = pd.read_csv(f"{DATA_DIR}/Thermistor_IJ_{token}.csv")

    n = min(len(abcd), len(efgh), len(ij))

    cores_5 = pd.concat([
        abcd.iloc[:n][["CH1_T_C", "CH2_T_C", "CH3_T_C", "CH4_T_C"]],
        efgh.iloc[:n][["CH1_T_C"]].rename(columns={"CH1_T_C": "CH5_T_C"}),
    ], axis=1)

    ambs_5 = pd.concat([
        efgh.iloc[:n][["CH2_T_C", "CH3_T_C", "CH4_T_C"]].rename(
            columns={"CH2_T_C": "A1", "CH3_T_C": "A2", "CH4_T_C": "A3"}),
        ij.iloc[:n][["CH1_T_C", "CH2_T_C"]].rename(
            columns={"CH1_T_C": "A4", "CH2_T_C": "A5"}),
    ], axis=1)

    room = ij.iloc[:n]["CH3_T_C"].astype(float)
    dt = parse_dt(abcd.iloc[:n]["datetime"])

    return pd.DataFrame({
        "T_core": cores_5.mean(axis=1).values,
        "T_ambient": ambs_5.mean(axis=1).values,
        "T_room": room.values,
        "datetime": dt.values,
        "sub_session": token,
    })


def load_200_overload() -> pd.DataFrame:
    """260316_Thtermistor_200_load.csv -- single-file format, CH1-5=core,
    CH6-10=ambient, CH11=room, 200Hz native. Downsampled to 1Hz (mean per
    integer second) so its physics-loss finite-difference step (dt) matches
    the other runs (~1Hz) instead of being 200x finer, which would make the
    d/dt term dominated by per-sample prediction noise rather than signal."""
    sf = pd.read_csv(f"{DATA_DIR}/260316_Thtermistor_200_load.csv")
    sf["t_sec_run"] = sf["Time"].astype(float)
    sf["T_core"] = sf[[f"CH{i}" for i in range(1, 6)]].mean(axis=1)
    sf["T_ambient"] = sf[[f"CH{i}" for i in range(6, 11)]].mean(axis=1)
    sf["T_room"] = sf["CH11"]
    sf["t_bucket"] = np.floor(sf["t_sec_run"]).astype(int)
    out = sf.groupby("t_bucket")[["T_core", "T_ambient", "T_room"]].mean().reset_index()
    out["t_sec_run"] = out["t_bucket"].astype(float)
    return out[["T_core", "T_ambient", "T_room", "t_sec_run"]]


def main():
    sub_tokens = sorted(
        re.sub(r"^.*Thermistor_ABCD_(.+)\.csv$", r"\1", f)
        for f in glob.glob(f"{DATA_DIR}/Thermistor_ABCD_*.csv")
    )
    print(f"{len(sub_tokens)} sub-sessions found: {sub_tokens}")

    per_sub = [load_thermistor_session(t) for t in sub_tokens]
    metas = sorted(
        [(s["datetime"].iloc[0], s["datetime"].iloc[-1], i) for i, s in enumerate(per_sub)],
        key=lambda x: x[0],
    )

    run_id, prev_end = -1, None
    run_loads = {}
    for st, en, idx in metas:
        if prev_end is None or (st - prev_end).total_seconds() / 60 > RUN_GAP_MIN:
            run_id += 1
            tok0 = per_sub[idx]["sub_session"].iloc[0]
            run_loads[run_id] = LOAD_MAPPING.get(date_prefix_for_token(tok0))
        per_sub[idx]["run_id"] = run_id
        per_sub[idx]["load_pct"] = run_loads[run_id]
        prev_end = en

    runs_df = []
    for rid, grp in pd.concat(per_sub, ignore_index=True).groupby("run_id"):
        g = grp.sort_values("datetime").reset_index(drop=True)
        t_s = (g["datetime"] - g["datetime"].iloc[0]).dt.total_seconds()
        runs_df.append(pd.DataFrame({
            "run_id": rid,
            "load_pct": g["load_pct"],
            "t_s": t_s,
            "T_core": g["T_core"],
            "T_ambient": g["T_ambient"],
            "T_room": g["T_room"],
        }))

    therm_df = pd.concat(runs_df, ignore_index=True)
    print(f"Thermistor bench runs: {therm_df['run_id'].nunique()} runs, {len(therm_df)} rows")
    print(therm_df.groupby(["run_id", "load_pct"]).size())

    # ---- 200% overload as its own run ----
    df_200 = load_200_overload()
    rid_200 = therm_df["run_id"].max() + 1
    df_200 = pd.DataFrame({
        "run_id": rid_200,
        "load_pct": 200,
        "t_s": df_200["t_sec_run"],
        "T_core": df_200["T_core"],
        "T_ambient": df_200["T_ambient"],
        "T_room": df_200["T_room"],
    })
    print(f"200% overload run: {len(df_200)} rows, T_core "
          f"{df_200['T_core'].min():.1f}-{df_200['T_core'].max():.1f}°C")

    full = pd.concat([therm_df, df_200], ignore_index=True).dropna()
    full = full[np.isfinite(full["T_core"]) & np.isfinite(full["T_ambient"]) & np.isfinite(full["T_room"])]
    full.to_csv(OUT_PATH, index=False)
    print(f"\nWrote {len(full)} rows over {full['run_id'].nunique()} runs to {OUT_PATH}")
    print(full.groupby(["run_id", "load_pct"]).agg(
        n=("t_s", "size"), dur_s=("t_s", "max"),
        T_core_min=("T_core", "min"), T_core_max=("T_core", "max"),
    ))


if __name__ == "__main__":
    main()
