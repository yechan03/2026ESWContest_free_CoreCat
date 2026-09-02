# -*- coding: utf-8 -*-
"""
Train all 5 models for PRIMARY_H=60s, save them, and measure weight file sizes.
Reports: joblib pickle size, raw float32 inference param size, int8 quantized estimate.
"""
import sys
import io
import os
import warnings
import pickle

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
warnings.filterwarnings('ignore')

import numpy as np
import pandas as pd
import joblib
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler, PolynomialFeatures
from sklearn.decomposition import PCA
from sklearn.linear_model import LinearRegression
from sklearn.tree import DecisionTreeRegressor
from sklearn.pipeline import Pipeline
from sklearn.metrics import mean_absolute_error, r2_score
from xgboost import XGBRegressor

ROOT = r"C:\jupyter\notebook\ml_validation"
CSV_PATH = os.path.join(ROOT, "data", "시나리오1-A-1파생 파라미터-온도 기반(05_04).csv")
OUT_DIR = os.path.join(ROOT, "output", "models")
os.makedirs(OUT_DIR, exist_ok=True)

# ── Hyperparams (same as notebook) ──────────────────────
HORIZONS = [30, 60, 120]
PRIMARY_H = 60
EMA_ALPHA = 0.1
DTDT_WINDOW = 3
FEATURE_COLS = ['T_core','T_ambient','T_room','dT_core_dt','dT_ambient_dt',
                'delta_core_amb','delta_core_room','T_core_ema','T_core_integral','t_sec']
TARGET = f'dT_core_{PRIMARY_H}'

# ── Load + feature engineering ──────────────────────────
RAW = pd.read_csv(CSV_PATH, encoding='cp949', header=None, skiprows=2)
cores = RAW[[10, 11, 12, 13, 15]].apply(pd.to_numeric, errors='coerce')
ambs  = RAW[[16, 17, 18, 20, 21]].apply(pd.to_numeric, errors='coerce')

raw = pd.DataFrame({
    'T_core':    cores.mean(axis=1),
    'T_ambient': ambs.mean(axis=1),
    'T_room':    pd.to_numeric(RAW[22], errors='coerce'),
    't_sec':     pd.to_numeric(RAW[7],  errors='coerce'),
}).dropna().reset_index(drop=True)

dt = raw['t_sec'].diff().median()
raw['dT_core_dt']    = raw['T_core'].diff(DTDT_WINDOW) / (DTDT_WINDOW * dt)
raw['dT_ambient_dt'] = raw['T_ambient'].diff(DTDT_WINDOW) / (DTDT_WINDOW * dt)
raw['delta_core_amb']  = raw['T_core'] - raw['T_ambient']
raw['delta_core_room'] = raw['T_core'] - raw['T_room']
raw['T_core_ema']      = raw['T_core'].ewm(alpha=EMA_ALPHA, adjust=False).mean()
raw['T_core_integral'] = ((raw['T_core'] - raw['T_room']) * dt).cumsum()
for h in HORIZONS:
    raw[f'dT_core_{h}'] = raw['T_core'].shift(-h) - raw['T_core']

df = raw[FEATURE_COLS + [TARGET]].dropna().reset_index(drop=True)
X, y = df[FEATURE_COLS].values, df[TARGET].values

X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, random_state=42)
scaler = StandardScaler()
X_train_sc = scaler.fit_transform(X_train)
X_test_sc  = scaler.transform(X_test)

# ── PCA n_components (95% rule, same as notebook) ───────
pca_full = PCA(n_components=len(FEATURE_COLS))
pca_full.fit(X_train_sc)
N_COMPONENTS = int(np.searchsorted(np.cumsum(pca_full.explained_variance_ratio_), 0.95)) + 1

# ── Define models ──────────────────────────────────────
models = {
    'Linear':            LinearRegression(),
    f'SVD({N_COMPONENTS})+LR': Pipeline([('pca', PCA(n_components=N_COMPONENTS)),
                                          ('lr',  LinearRegression())]),
    'Poly(2)+LR':        Pipeline([('poly', PolynomialFeatures(degree=2, include_bias=False)),
                                   ('lr',   LinearRegression())]),
    'DT(d=5)':           DecisionTreeRegressor(max_depth=5, random_state=42),
    'XGB(200,d=4)':      XGBRegressor(n_estimators=200, learning_rate=0.05, max_depth=4,
                                      random_state=42, verbosity=0),
}

# ── Inference parameter extraction ─────────────────────
def count_inference_params(name, model):
    """Return (n_params, breakdown_str) for the parameters that go into ESP32."""
    if name == 'Linear':
        n = model.coef_.size + 1                    # coefs + intercept
        return n, f"coef({model.coef_.size}) + bias(1)"
    if name.startswith('SVD'):
        pca = model.named_steps['pca']
        lr  = model.named_steps['lr']
        comps = pca.components_.size                # k × n_features
        mean  = pca.mean_.size                      # n_features (centering)
        coef  = lr.coef_.size                       # k
        n = comps + mean + coef + 1
        return n, f"pca_comp({comps}) + mean({mean}) + lr_coef({coef}) + bias(1)"
    if name.startswith('Poly'):
        poly = model.named_steps['poly']
        lr   = model.named_steps['lr']
        n = lr.coef_.size + 1
        return n, f"poly_coef({lr.coef_.size}) + bias(1)  [expanded {poly.n_output_features_} terms]"
    if name.startswith('DT'):
        tree = model.tree_
        # 노드당: feature_idx(int) + threshold(float) + value(float) + left(int) + right(int)
        n_nodes = tree.node_count
        return n_nodes * 5, f"{n_nodes} nodes × 5 fields"
    if name.startswith('XGB'):
        booster = model.get_booster()
        df_trees = booster.trees_to_dataframe()
        n_nodes = len(df_trees)
        return n_nodes * 5, f"{n_nodes} nodes × 5 fields ({df_trees['Tree'].nunique()} trees)"
    return None, "?"


def fmt_bytes(n):
    if n < 1024:        return f"{n:>7d} B"
    if n < 1024**2:     return f"{n/1024:>6.2f} KB"
    return f"{n/1024**2:>6.2f} MB"


print(f"\n{'='*100}")
print(f"모델 가중치 비교 (PRIMARY_H = {PRIMARY_H}s, target = ΔT_core)")
print(f"학습 샘플: {X_train_sc.shape[0]}, 테스트 샘플: {X_test_sc.shape[0]}, 피처: {len(FEATURE_COLS)}")
print(f"{'='*100}")
print(f"{'Model':<16} {'MAE':>7} {'R²':>7} {'#params':>10} "
      f"{'float32':>11} {'int8':>10} {'pickle':>11} {'breakdown'}")
print('-' * 100)

results = []
for name, model in models.items():
    model.fit(X_train_sc, y_train)
    yp = model.predict(X_test_sc)
    mae = mean_absolute_error(y_test, yp)
    r2  = r2_score(y_test, yp)

    n_params, breakdown = count_inference_params(name, model)
    float32_bytes = n_params * 4
    int8_bytes    = n_params * 1   # 양자화 시 가중치만, scale/zero-point 제외 추정

    fname = name.replace('+','_').replace('(','').replace(')','').replace(',','_').replace(' ','')
    pkl_path = os.path.join(OUT_DIR, f"{fname}.pkl")
    joblib.dump(model, pkl_path, compress=3)
    pkl_size = os.path.getsize(pkl_path)

    print(f"{name:<16} {mae:>7.4f} {r2:>7.4f} {n_params:>10,d} "
          f"{fmt_bytes(float32_bytes)} {fmt_bytes(int8_bytes)} {fmt_bytes(pkl_size)}  {breakdown}")
    results.append({'model': name, 'mae': mae, 'r2': r2, 'params': n_params,
                    'float32': float32_bytes, 'int8': int8_bytes, 'pkl': pkl_size})

# Save scaler too (필요 — 입력 정규화용)
sc_path = os.path.join(OUT_DIR, "scaler.pkl")
joblib.dump(scaler, sc_path, compress=3)
sc_size = os.path.getsize(sc_path)
sc_params = scaler.mean_.size + scaler.scale_.size  # mean + std
print('-' * 100)
print(f"{'StandardScaler':<16} {'-':>7} {'-':>7} {sc_params:>10,d} "
      f"{fmt_bytes(sc_params*4)} {fmt_bytes(sc_params*1)} {fmt_bytes(sc_size)}  "
      f"mean({scaler.mean_.size}) + scale({scaler.scale_.size})")
print('=' * 100)

# Reference: 1D-CNN 분석값
print("\n[참고: 같은 작업에 1D-CNN 적용한다면]")
print(f"{'Conv1D(8,k=3) + Dense(16) + Dense(1)':<40} 약 200 params  →  float32 800 B / int8 200 B")
print(f"{'(실제 .tflite 파일은 헤더/메타 포함 ~3-5 KB)'}")

print(f"\n저장 위치: {OUT_DIR}")
for f in sorted(os.listdir(OUT_DIR)):
    p = os.path.join(OUT_DIR, f)
    print(f"  {f:<30} {fmt_bytes(os.path.getsize(p))}")
