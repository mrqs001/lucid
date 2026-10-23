# /// script
# requires-python = ">=3.10"
# dependencies = ["matplotlib>=3.8", "numpy", "pandas"]
# ///
"""Charts for the Walch fit (docs/MODEL.md).

    uv run tools/walch_charts.py <walch_out_dir> <chart_dir>

Reads walch-report.json and walch-epochs.csv written by `core/eval walch`.
"""
import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK2 = "#52514e"
MUTED = "#898781"
GRID = "#e1e0d9"
AXIS = "#c3c2b7"
NEW = "#2a78d6"  # fitted model
OLD = MUTED  # hand-set model
STAGES = ["wake", "light", "deep", "rem"]
LABELS = {"wake": "Wake", "light": "Light (N1+N2)", "deep": "Deep (N3)", "rem": "REM"}

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "axes.edgecolor": AXIS, "axes.labelcolor": INK2, "xtick.color": MUTED, "ytick.color": MUTED,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8, "axes.axisbelow": True,
    "axes.spines.top": False, "axes.spines.right": False, "font.size": 10,
    "axes.titlesize": 11, "axes.titleweight": "bold", "axes.titlecolor": INK, "axes.titlelocation": "left",
    "legend.frameon": False, "legend.labelcolor": INK2, "lines.linewidth": 2, "lines.solid_capstyle": "round",
})


def title(fig, text, sub):
    fig.text(0.01, 0.985, text, fontsize=13, fontweight="bold", color=INK, va="top")
    fig.text(0.01, 0.94, sub, fontsize=10, color=INK2, va="top")


def example_night(d, report, out):
    per = pd.DataFrame(report["perSubject"])
    per = per[per.epochs > 800]
    sid = per.iloc[(per.chosenKappa - per.chosenKappa.median()).abs().argsort().iloc[0]].id
    n = d[d.subject.astype(str) == str(sid)].sort_values("epoch")
    h = n.hours_from_psg_start
    fig, (a1, a2) = plt.subplots(2, 1, figsize=(10, 5.2), sharex=True, gridspec_kw={"height_ratios": [1, 1.4]})
    level = n.stage.map({"wake": 3, "rem": 2, "light": 1, "deep": 0})
    a1.step(h, level, where="post", color=INK2, linewidth=1.5)
    a1.set_yticks([0, 1, 2, 3], ["Deep", "Light", "REM", "Wake"])
    a1.set_title("Sleep lab (PSG) stages")
    a1.grid(axis="y", visible=False)
    rem = (n.stage == "rem").to_numpy()
    for ax in (a1, a2):
        ax.fill_between(h, 0, 1, where=rem, step="post", color=NEW, alpha=0.10, transform=ax.get_xaxis_transform(), linewidth=0)
    a2.plot(h, n.p_rem_handset, color=OLD, linewidth=1.5, label="hand-set model")
    a2.plot(h, n.p_rem, color=NEW, label="fitted model (never saw this night)")
    a2.axhline(0.7, color=AXIS, linewidth=1)
    a2.text(0.02, 0.72, "cue threshold 0.7", color=MUTED, ha="left", va="bottom", fontsize=9)
    a2.set_ylim(0, 1.02)
    a2.set_ylabel("p(REM)")
    a2.set_xlabel("hours since lights off")
    a2.set_title("Estimated probability of REM, computed live (past data only)", pad=30)
    a2.legend(loc="lower left", bbox_to_anchor=(0, 1.01), ncol=2, borderaxespad=0)
    title(fig, f"One lab night (subject {sid}, median accuracy)", "Blue band: REM according to the sleep lab")
    fig.tight_layout(rect=(0, 0, 1, 0.92))
    fig.savefig(out / "walch-example-night.png", dpi=150)
    plt.close(fig)


def features(d, out):
    cols = [("hr_rel", "Heart rate above\nthe night's baseline (bpm)", (-10, 25)),
            ("hr_instability_rel", "Heart-rate irregularity vs\nthe night's median (ln ratio)", (-2, 3)),
            ("minutes_still", "Minutes since\nthe last movement", (0, 60))]
    fig, axes = plt.subplots(1, 3, figsize=(11, 3.8))
    for ax, (c, label, lim) in zip(axes, cols):
        data = [d.loc[d.stage == s, c].dropna() for s in STAGES]
        bp = ax.boxplot(data, widths=0.45, showfliers=False, patch_artist=True, whis=(10, 90),
                        medianprops={"color": INK, "linewidth": 2}, boxprops={"facecolor": NEW, "alpha": 0.25, "edgecolor": NEW},
                        whiskerprops={"color": NEW}, capprops={"color": NEW})
        ax.set_xticks(range(1, 5), ["Wake", "Light", "Deep", "REM"])
        ax.set_ylim(*lim)
        ax.set_title(label, fontsize=10)
        ax.grid(axis="x", visible=False)
    title(fig, "What each signal says about the stage (31 lab nights)", "Boxes: middle half of 30 s epochs; whiskers: 10th to 90th percentile")
    fig.tight_layout(rect=(0, 0, 1, 0.84))
    fig.savefig(out / "walch-features.png", dpi=150)
    plt.close(fig)


def calibration(d, out):
    bins = np.linspace(0, 1, 11)
    fig, ax = plt.subplots(figsize=(5.6, 5))
    ax.plot([0, 1], [0, 1], color=AXIS, linewidth=1)
    ax.text(0.97, 0.9, "perfect calibration", color=MUTED, rotation=42, ha="right", fontsize=9)
    for col, color, name in (("p_rem_handset", OLD, "hand-set"), ("p_rem", NEW, "fitted")):
        x = d[[col, "stage"]].dropna()
        idx = np.clip(np.digitize(x[col], bins) - 1, 0, 9)
        g = x.groupby(idx).agg(p=(col, "mean"), obs=("stage", lambda s: (s == "rem").mean()), n=(col, "size"))
        g = g[g.n >= 50]
        ax.plot(g.p, g.obs, color=color, marker="o", markersize=6, markeredgecolor=SURFACE, markeredgewidth=2, label=name)
        ax.annotate(name, (g.p.iloc[-1], g.obs.iloc[-1]), xytext=(6, -4), textcoords="offset points", color=INK2, fontsize=9)
    ax.set_xlim(0, 1.05)
    ax.set_ylim(0, 1.05)
    ax.set_xlabel("model's p(REM)")
    ax.set_ylabel("share of those epochs that were REM in the lab")
    ax.legend(loc="upper left")
    title(fig, "Does p(REM) mean what it says?", "Held-out epochs, binned by predicted probability")
    fig.tight_layout(rect=(0, 0, 1, 0.88))
    fig.savefig(out / "walch-calibration.png", dpi=150)
    plt.close(fig)


def cue_by_hour(d, report, out):
    hours = pd.DataFrame(report["models"][report["chosen"]]["wouldCueAllNightByHour"])
    hours = hours[(hours.hoursSinceOnset >= 0) & (hours.cues >= 10)]
    onset = d[d.stage.isin(["light", "deep", "rem"])].groupby("subject").epoch.min()
    e = d[d.stage.notna()].copy()
    e["h"] = ((e.epoch - e.subject.map(onset)) * 30 / 3600).floordiv(1)
    base = e[e.h >= 0].groupby("h").stage.apply(lambda s: (s == "rem").mean())
    fig, ax = plt.subplots(figsize=(8, 4.2))
    ax.plot(base.index + 0.5, base.values, color=OLD, marker="o", markersize=6, markeredgecolor=SURFACE, markeredgewidth=2,
            label="share of epochs that are REM (chance level)")
    ax.plot(hours.hoursSinceOnset + 0.5, hours.inRem, color=NEW, marker="o", markersize=6, markeredgecolor=SURFACE,
            markeredgewidth=2, label="cues that land in REM (fitted model)")
    for _, r in hours.iterrows():
        ax.annotate(f"{int(r.cues)}", (r.hoursSinceOnset + 0.5, r.inRem), xytext=(0, 8), textcoords="offset points",
                    ha="center", color=MUTED, fontsize=8)
    ax.axvline(5, color=AXIS, linewidth=1)
    ax.text(5.05, 0.04, "current gate: 5 h", color=MUTED, fontsize=9)
    ax.set_ylim(0, 1.05)
    ax.set_xlim(0, 8)
    ax.set_xlabel("hours since sleep onset")
    ax.set_ylabel("share")
    ax.legend(loc="center right")
    title(fig, "Cue accuracy through the night (31 lab nights, all other rules on)", "Small numbers: would-be cues in that hour")
    fig.tight_layout(rect=(0, 0, 1, 0.88))
    fig.savefig(out / "walch-cue-by-hour.png", dpi=150)
    plt.close(fig)


def subjects(report, out):
    per = pd.DataFrame(report["perSubject"]).sort_values("chosenKappa").reset_index(drop=True)
    fig, ax = plt.subplots(figsize=(8, 7))
    y = np.arange(len(per))
    ax.hlines(y, per.handSetKappa, per.chosenKappa, color=GRID, linewidth=2)
    ax.scatter(per.handSetKappa, y, s=40, color=OLD, edgecolor=SURFACE, linewidth=1.5, label="hand-set", zorder=3)
    ax.scatter(per.chosenKappa, y, s=40, color=NEW, edgecolor=SURFACE, linewidth=1.5, label="fitted (held out)", zorder=3)
    ax.set_yticks(y, per.id, fontsize=8)
    ax.set_xlabel("Cohen's kappa, 4 stages (0 = chance, 1 = perfect)")
    ax.axvline(0, color=AXIS, linewidth=1)
    ax.grid(axis="y", visible=False)
    ax.legend(loc="lower right")
    better = int((per.chosenKappa > per.handSetKappa).sum())
    title(fig, "Every night, before and after fitting", f"Fitted model better on {better} of {len(per)} nights")
    fig.tight_layout(rect=(0, 0, 1, 0.93))
    fig.savefig(out / "walch-subjects.png", dpi=150)
    plt.close(fig)


def main():
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    dst.mkdir(parents=True, exist_ok=True)
    report = json.loads((src / "walch-report.json").read_text())
    d = pd.read_csv(src / "walch-epochs.csv")
    example_night(d, report, dst)
    features(d, dst)
    calibration(d, dst)
    cue_by_hour(d, report, dst)
    subjects(report, dst)
    print(f"charts written to {dst}")


if __name__ == "__main__":
    main()
