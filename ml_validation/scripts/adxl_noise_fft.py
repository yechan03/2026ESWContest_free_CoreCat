#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
adxl_noise_fft.py — IN-GPS ADXL335 노이즈 FFT/PSD 분석기

ESP32-S3(ULP ADC)로 캡처한 raw counts CSV, 또는 오실로스코프로 받은
아날로그 출력(volts) txt 둘 다 처리한다. 정지 상태 데이터를 받아
Welch PSD를 그리고, µg/√Hz로 환산해 ADXL335 데이터시트 노이즈 플로어와
비교하며, 50/60Hz·BLE 사이클 같은 주기성 피크를 자동 검출한다.

핵심 진단 질문:
  1) 측정 노이즈 밀도(µg/√Hz)가 데이터시트(X/Y 150, Z 300)보다
     훨씬 높은가? → 그렇다면 센서가 아니라 ADC/시스템 노이즈가 지배.
  2) 스펙트럼이 화이트(평탄)한가, 특정 피크가 있는가?
     - 평탄+높음 → SAR ADC 노이즈, 오버샘플링/디커플링 필요
     - 50/60Hz 및 배수 → 전원/접지 커플링(mains)
     - BLE 광고 인터벌/5.5s ON-OFF 주파수 → TX 전류 스파이크 침투

사용 예:
  # ESP32 raw counts 캡처(헤더 있는 CSV, 200Hz)
  python adxl_noise_fft.py capture.csv --fs 200 --units counts \
      --sens-x 406.845 --sens-y 407.095 --sens-z 399.405

  # 오실로스코프 아날로그 출력(volts). fs는 헤더 신뢰 불가 → 직접 지정
  python adxl_noise_fft.py ../data/Vibration_data_norm_05_12_09_51.txt \
      --units volts --fs 3200 --mv-per-g 330

의존성: numpy, scipy, matplotlib, pandas
"""

import argparse
import os
import re
import sys

import numpy as np

try:
    from scipy import signal
except Exception:  # pragma: no cover
    print("scipy가 필요해: pip install scipy --break-system-packages", file=sys.stderr)
    raise

import matplotlib
matplotlib.use("Agg")  # 헤드리스 환경
import matplotlib.pyplot as plt

# ADXL335 데이터시트 typ 노이즈 밀도 (µg/√Hz rms)
ADXL335_NOISE_DENSITY = {"X": 150.0, "Y": 150.0, "Z": 300.0}

AXES = ["X", "Y", "Z"]


# ---------------------------------------------------------------------------
# 입력 파싱
# ---------------------------------------------------------------------------
def load_data(path, max_cols=8):
    """
    숫자 행만 추출해 (N, ncol) float 배열로 반환. '#' 주석/헤더 라인은 건너뜀.
    구분자는 콤마/공백/탭 자동 처리. 빈 셀은 NaN.
    """
    rows = []
    header_cols = None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            s = line.strip()
            if not s:
                continue
            if s.startswith("#"):
                # 컬럼 힌트 기록(진단용)
                if "olumn" in s or "Column" in s:
                    header_cols = s
                continue
            # 콤마/공백/탭 혼용 분리
            parts = re.split(r"[,\t ]+", s)
            vals = []
            for p in parts[:max_cols]:
                p = p.strip()
                if p == "" or p in ("nan", "NaN"):
                    vals.append(np.nan)
                else:
                    try:
                        vals.append(float(p))
                    except ValueError:
                        vals.append(np.nan)
            if vals:
                rows.append(vals)
    if not rows:
        raise ValueError(f"숫자 데이터를 못 찾음: {path}")
    width = max(len(r) for r in rows)
    arr = np.full((len(rows), width), np.nan)
    for i, r in enumerate(rows):
        arr[i, : len(r)] = r
    return arr, header_cols


def pick_axis_columns(arr, has_time):
    """
    X/Y/Z 컬럼 인덱스를 추정.
      - has_time=True  → 0번이 시간, 1/2/3이 X/Y/Z
      - has_time=False → 0/1/2가 X/Y/Z
    실제 비어있지 않은(분산>0) 컬럼만 사용.
    """
    start = 1 if has_time else 0
    idx = list(range(start, start + 3))
    # 안전장치: 범위 밖이면 클램프
    idx = [i for i in idx if i < arr.shape[1]]
    return idx


# ---------------------------------------------------------------------------
# 환산: 입력 단위 → mg (DC 제거)
# ---------------------------------------------------------------------------
def to_mg(series, units, sens_counts_per_g=None, mv_per_g=None,
          adc_ref_mv=3300.0, adc_max=4095.0):
    """
    한 축 시계열을 mg(AC 성분)로 변환. DC(평균)는 zero offset으로 보고 제거.
    units:
      counts : ESP32 ADC raw. sens_counts_per_g 사용.
      volts  : 스코프 아날로그 출력(V). mv_per_g 사용.
      mg     : 이미 mg. 그대로(평균만 제거).
    """
    x = np.asarray(series, dtype=float)
    x = x[~np.isnan(x)]
    if units == "counts":
        if not sens_counts_per_g:
            raise ValueError("counts 입력엔 --sens-* (counts/g)가 필요해")
        mg = (x - x.mean()) / sens_counts_per_g * 1000.0
    elif units == "volts":
        if not mv_per_g:
            raise ValueError("volts 입력엔 --mv-per-g가 필요해")
        mg = (x - x.mean()) * 1000.0 / mv_per_g * 1000.0  # V→mV→g→mg
    elif units == "mg":
        mg = x - x.mean()
    else:
        raise ValueError(f"알 수 없는 units: {units}")
    return mg


# ---------------------------------------------------------------------------
# 스펙트럼 분석
# ---------------------------------------------------------------------------
def welch_asd(mg, fs, nperseg=None):
    """
    Welch PSD → 진폭 스펙트럼 밀도(ASD, mg/√Hz)와 µg/√Hz 반환.
    nperseg는 데이터 길이에 맞춰 자동(최대 4096, 최소 256).
    """
    n = len(mg)
    if nperseg is None:
        nperseg = int(min(4096, max(256, 2 ** int(np.log2(max(256, n // 8))))))
        nperseg = min(nperseg, n)
    f, psd = signal.welch(mg, fs=fs, window="hann", nperseg=nperseg,
                          noverlap=nperseg // 2, detrend="constant",
                          scaling="density")
    asd_mg = np.sqrt(psd)              # mg/√Hz
    asd_ug = asd_mg * 1000.0          # µg/√Hz
    return f, psd, asd_mg, asd_ug


def band_rms(mg, fs, f_lo=0.5, f_hi=None):
    """[f_lo, f_hi] 대역 내 RMS(mg). f_hi 기본 = Nyquist."""
    if f_hi is None:
        f_hi = fs / 2.0
    f, psd, _, _ = welch_asd(mg, fs)
    sel = (f >= f_lo) & (f <= f_hi)
    # NumPy 2.0에서 np.trapz → np.trapezoid 로 변경됨. 버전 무관 처리.
    _trap = getattr(np, "trapezoid", None) or np.trapz
    rms = np.sqrt(_trap(psd[sel], f[sel]))
    return rms


def detect_peaks(f, asd_ug, fs, prominence_ratio=3.0, n_top=8):
    """
    중앙값 대비 두드러진 스펙트럼 피크 검출 → (freq, ug, 라벨) 리스트.
    DC 근처(<0.3Hz)는 제외. 알려진 소스(50/60Hz·배수, BLE 사이클)에 라벨링.
    """
    med = np.median(asd_ug[f > 0.3])
    sel = f > 0.3
    fpk, apk = f[sel], asd_ug[sel]
    peaks, props = signal.find_peaks(apk, height=med * prominence_ratio,
                                     distance=max(1, len(apk) // 200))
    order = np.argsort(props["peak_heights"])[::-1][:n_top]
    out = []
    for p in np.array(peaks)[order]:
        fr, am = fpk[p], apk[p]
        out.append((fr, am, label_freq(fr)))
    out.sort()
    return out, med


def label_freq(fr):
    """주파수에 알려진 노이즈 소스 라벨 추정."""
    tags = []
    for base, name in ((50.0, "mains50"), (60.0, "mains60")):
        for h in range(1, 6):
            if abs(fr - base * h) <= 1.0:
                tags.append(f"{name}x{h}")
    # BLE 광고 사이클: 0.5s ON / 5s OFF → 주기 ~5.5s → ~0.18Hz
    if abs(fr - 1.0 / 5.5) <= 0.03:
        tags.append("BLE_ON/OFF~5.5s")
    if abs(fr - 0.333) <= 0.03:
        tags.append("light_sleep~3s?")
    return ",".join(tags) if tags else ""


# ---------------------------------------------------------------------------
# 리포트 + 플롯
# ---------------------------------------------------------------------------
def analyze(path, fs, units, sens, mv_per_g, outdir, adc_ref_mv, adc_max,
            has_time):
    arr, header = load_data(path)
    cols = pick_axis_columns(arr, has_time)
    base = os.path.splitext(os.path.basename(path))[0]
    os.makedirs(outdir, exist_ok=True)

    lines = []
    def log(s=""):
        print(s)
        lines.append(s)

    log("=" * 70)
    log(f"파일      : {path}")
    log(f"행 수     : {arr.shape[0]}, 컬럼 수: {arr.shape[1]}")
    log(f"fs        : {fs} Hz   (Nyquist {fs/2:.1f} Hz)")
    log(f"입력 단위 : {units}   시간컬럼: {'있음' if has_time else '없음'}")
    if header:
        log(f"헤더 힌트 : {header}")
    log("=" * 70)

    results = {}
    fig, axs = plt.subplots(3, 1, figsize=(11, 12), sharex=True)

    for k, ci in enumerate(cols):
        axis = AXES[k] if k < 3 else f"C{ci}"
        raw = arr[:, ci]
        if np.all(np.isnan(raw)):
            log(f"[{axis}] 컬럼 {ci} 비어있음 — 건너뜀")
            continue
        sc = sens.get(axis)
        mg = to_mg(raw, units, sens_counts_per_g=sc, mv_per_g=mv_per_g,
                   adc_ref_mv=adc_ref_mv, adc_max=adc_max)
        if len(mg) < 32:
            log(f"[{axis}] 샘플 부족({len(mg)}) — 건너뜀")
            continue

        f, psd, asd_mg, asd_ug = welch_asd(mg, fs)
        # 평탄 영역(상위 20~80% 주파수) 노이즈밀도 중앙값
        midsel = (f > fs * 0.05) & (f < fs * 0.45)
        floor_ug = np.median(asd_ug[midsel]) if midsel.any() else np.median(asd_ug)
        rms = band_rms(mg, fs)
        peaks, med = detect_peaks(f, asd_ug, fs)
        ds = ADXL335_NOISE_DENSITY.get(axis, np.nan)
        ratio = floor_ug / ds if ds else np.nan

        results[axis] = dict(floor_ug=floor_ug, rms=rms, ratio=ratio, peaks=peaks)

        log(f"\n[{axis}축]")
        log(f"  시간영역 std         : {mg.std():.2f} mg")
        log(f"  대역 RMS(0.5~Nyq)    : {rms:.2f} mg")
        log(f"  노이즈밀도(중앙)     : {floor_ug:.0f} µg/√Hz")
        log(f"  데이터시트 typ       : {ds:.0f} µg/√Hz  → 측정/시트 = {ratio:.1f}x")
        if ratio and ratio > 2:
            log(f"  ⚠ 시트의 {ratio:.0f}배 → 센서가 아니라 ADC/시스템 노이즈 지배 의심")
        if peaks:
            log("  주요 피크:")
            for fr, am, tag in peaks:
                log(f"     {fr:7.2f} Hz  {am:8.0f} µg/√Hz  {tag}")
        else:
            log("  뚜렷한 피크 없음(화이트 노이즈 경향)")

        ax = axs[k]
        ax.semilogy(f, asd_ug, lw=0.8, label=f"{axis} measured")
        ax.axhline(ds, color="g", ls="--", lw=1, label=f"ADXL335 typ {ds:.0f}")
        ax.axhline(floor_ug, color="r", ls=":", lw=1,
                   label=f"meas floor {floor_ug:.0f}")
        for fr, am, tag in peaks:
            ax.annotate(f"{fr:.1f}Hz" + (f"\n{tag}" if tag else ""),
                        xy=(fr, am), fontsize=7, color="purple",
                        xytext=(fr, am * 1.6), ha="center")
        ax.set_ylabel("ug/sqrt(Hz)")
        ax.set_title(f"{axis}-axis ASD")
        ax.grid(True, which="both", alpha=0.3)
        ax.legend(fontsize=8, loc="upper right")

    axs[-1].set_xlabel("Frequency (Hz)")
    fig.suptitle(f"ADXL335 noise ASD - {base}  (fs={fs}Hz)", y=0.995)
    fig.tight_layout(rect=[0, 0, 1, 0.99])
    figpath = os.path.join(outdir, f"{base}_asd.png")
    fig.savefig(figpath, dpi=130)
    plt.close(fig)
    log(f"\n그래프 저장: {figpath}")

    # 요약 텍스트
    txtpath = os.path.join(outdir, f"{base}_report.txt")
    with open(txtpath, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    log(f"리포트 저장: {txtpath}")

    # 종합 진단 한 줄
    if results:
        worst = max(results.values(), key=lambda d: d["ratio"] if d["ratio"] else 0)
        log("\n" + "-" * 70)
        if worst["ratio"] and worst["ratio"] > 2:
            log("진단: 측정 노이즈밀도가 데이터시트를 크게 상회 → ADC/시스템 노이즈 우세.")
            log("      대책: ULP 오버샘플링(채널당 N회 평균), ADXL Vs 디커플링/별도 레일,")
            log("            안티앨리어싱 캡(Cx) 점검, 50/60Hz·BLE 피크 보이면 접지/전원 분리.")
        else:
            log("진단: 노이즈밀도가 데이터시트 근처 → 센서 한계에 가까움. 추가 필터로 충분.")
    return results


def main():
    ap = argparse.ArgumentParser(description="ADXL335 노이즈 FFT/PSD 분석기")
    ap.add_argument("files", nargs="+", help="입력 CSV/TXT (여러 개 가능)")
    ap.add_argument("--fs", type=float, required=True, help="샘플링 주파수(Hz)")
    ap.add_argument("--units", choices=["counts", "volts", "mg"], default="counts")
    ap.add_argument("--has-time", dest="has_time", action="store_true",
                    help="첫 컬럼이 시간(기본 자동: volts/헤더있으면 시간으로 가정)")
    ap.add_argument("--no-time", dest="has_time", action="store_false")
    ap.set_defaults(has_time=None)
    # counts 환산용 감도 (counts/g)
    ap.add_argument("--sens-x", type=float, default=406.845)
    ap.add_argument("--sens-y", type=float, default=407.095)
    ap.add_argument("--sens-z", type=float, default=399.405)
    # volts 환산용 감도
    ap.add_argument("--mv-per-g", type=float, default=330.0)
    ap.add_argument("--adc-ref-mv", type=float, default=3300.0)
    ap.add_argument("--adc-max", type=float, default=4095.0)
    ap.add_argument("--outdir", default=None, help="출력 폴더(기본: 입력파일 옆/figures)")
    args = ap.parse_args()

    sens = {"X": args.sens_x, "Y": args.sens_y, "Z": args.sens_z}

    for path in args.files:
        if not os.path.exists(path):
            print(f"없는 파일: {path}", file=sys.stderr)
            continue
        # 시간컬럼 자동 추정: 명시 없으면 volts(스코프)는 시간 있음, counts는 없음
        has_time = args.has_time
        if has_time is None:
            has_time = (args.units == "volts")
        outdir = args.outdir or os.path.join(os.path.dirname(os.path.abspath(path)),
                                             "..", "output", "figures")
        analyze(path, args.fs, args.units, sens, args.mv_per_g, outdir,
                args.adc_ref_mv, args.adc_max, has_time)


if __name__ == "__main__":
    main()
