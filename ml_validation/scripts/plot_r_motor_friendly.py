"""
plot_r_motor_friendly.py
IN-GPS capstone -- a presentation-friendly redraw of fit_motor_thermal_resistance.py's
scatter+fit figure. Same numbers, same computation (imports and reuses it directly --
no recomputation, no risk of drifting from the source-of-truth fit); this script only
changes the PRESENTATION: plain-language title/axis labels, direct on-chart annotation
of what the slope and intercept mean, larger type, a validated 2-color categorical
palette (dataviz skill default) instead of default matplotlib colors.

Usage: python plot_r_motor_friendly.py
Output: ../output/figures/17_r_motor_fit_friendly.png
"""

import numpy as np
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm

import fit_motor_thermal_resistance as rfit

FIG_PATH = "../output/figures/17_r_motor_fit_friendly.png"

# dataviz skill default palette (validated categorical order + chart chrome)
BLUE = "#2a78d6"     # slot 1 -- used in fit
ORANGE = "#eb6834"   # slot 2 -- excluded
INK_PRIMARY = "#0b0b0b"
INK_SECONDARY = "#52514e"
INK_MUTED = "#898781"
GRID = "#e1e0d9"
SURFACE = "#fcfcfb"

plt.rcParams["font.family"] = "sans-serif"
plt.rcParams["font.sans-serif"] = ["Malgun Gothic", "Segoe UI", "Arial"]
plt.rcParams["axes.unicode_minus"] = False


def main():
    fit_df, a, R_motor, r2 = rfit.main()

    used = fit_df[~fit_df["excluded"]]
    excl = fit_df[fit_df["excluded"]]

    fig, ax = plt.subplots(figsize=(10, 7.2), dpi=150)
    fig.patch.set_facecolor(SURFACE)
    ax.set_facecolor(SURFACE)

    # ---- fit line first (under the points) ----
    p_line = np.linspace(0, fit_df["P_W"].max() * 1.04, 50)
    ax.plot(p_line, a + R_motor * p_line, color=BLUE, linestyle="--",
             linewidth=2, zorder=2, label=None)

    # ---- points ----
    ax.scatter(used["P_W"], used["dT_inf"], s=170, color=BLUE,
               edgecolor="white", linewidth=1.3, zorder=3, label="정상상태 실측 (회귀에 사용)")
    ax.scatter(excl["P_W"], excl["dT_inf"], s=170, marker="X", color=ORANGE,
               edgecolor="white", linewidth=1.3, zorder=3, label="측정 시간 부족 (제외)")

    for _, r in fit_df.iterrows():
        dy = 14 if not r["excluded"] else 14
        ax.annotate(f"부하 {r['load_pct']:.0f}%", (r["P_W"], r["dT_inf"]),
                    textcoords="offset points", xytext=(0, dy), ha="center",
                    fontsize=11, color=INK_SECONDARY)

    # ---- direct annotation: slope = R_motor ----
    ax.annotate(
        f"기울기 = 열저항 R_motor\n= {R_motor:.4f} °C/W",
        xy=(420, a + R_motor * 420), xytext=(420, 55),
        fontsize=13, color=BLUE, ha="center", fontweight="bold",
        arrowprops=dict(arrowstyle="-|>", color=BLUE, lw=1.6),
    )

    # ---- direct annotation: intercept ----
    ax.annotate(
        f"부하 0%에도 {a:.1f}°C ↑\n(정격속도로 계속 도는\n무부하 기본 발열)",
        xy=(0, a), xytext=(150, 4),
        fontsize=11.5, color=INK_SECONDARY, ha="left",
        arrowprops=dict(arrowstyle="-|>", color=INK_MUTED, lw=1.4),
    )

    # ---- headline + subtitle ----
    ax.set_title(
        "부하를 더 걸수록, 모터는 실내보다 얼마나 더 뜨거워지는가",
        fontsize=17, fontweight="bold", color=INK_PRIMARY, pad=42, loc="left",
    )
    ax.text(0, 1.045, f"R² = {r2:.2f}  ·  6개 부하 조건 모두 오차 ±0.6°C 이내",
            transform=ax.transAxes, fontsize=12, color=INK_SECONDARY, va="bottom")

    ax.set_xlabel("모터에 넣어준 전력 [W]  (부하율 × Labvolt 8960 정격 350W)",
                  fontsize=13, color=INK_PRIMARY, labelpad=10)
    ax.set_ylabel("실내 대비 온도 상승 [°C]  (코어 - 실내, 정상상태)",
                  fontsize=13, color=INK_PRIMARY, labelpad=12)

    ax.tick_params(colors=INK_MUTED, labelsize=11)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    for spine in ("left", "bottom"):
        ax.spines[spine].set_color(GRID)
    ax.grid(True, color=GRID, linewidth=1, zorder=0)
    ax.set_axisbelow(True)

    ax.set_ylim(-2, 95)
    ax.set_xlim(-25, 760)

    legend = ax.legend(loc="upper left", frameon=False, fontsize=12,
                        handletextpad=0.6, borderaxespad=0)
    for text in legend.get_texts():
        text.set_color(INK_PRIMARY)

    plt.tight_layout()
    plt.savefig(FIG_PATH, facecolor=SURFACE, dpi=150)
    print(f"Saved {FIG_PATH}")


if __name__ == "__main__":
    main()
