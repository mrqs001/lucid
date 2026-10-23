//! walch-prep: turn the Walch et al. (2019) sleep-accel dataset (PhysioNet) into Lucid replay inputs.
//!
//!     cargo run --release -- <dataset_dir> <out_dir>
//!
//! For every subject it writes:
//!   <id>.json        {"sessionStartMs": .., "samples": [..]} in Lucid's wire format, ready for
//!                    `./gradlew :core:eval:run --args="replay ..."` and the walch-* commands
//!   <id>.labels.csv  epoch,stage   (30 s PSG epochs: 0 wake, 1 N1, 2 N2, 3 N3, 4 N4, 5 REM, -1 unscored)
//! plus summary.json with per-subject data statistics.
//!
//! The night is cropped to the PSG-scored window (the heart-rate files also hold days of data
//! before the study). Motion is reduced to one summary per second with exactly the arithmetic of
//! the watch app's MotionSummarizer.kt; heart rate (Apple Watch, every few seconds) is put on a
//! 1 Hz grid by linear interpolation across gaps of at most MAX_HR_GAP_S.

use anyhow::{Context, Result, bail};
use rayon::prelude::*;
use serde::Serialize;
use std::fs;
use std::path::{Path, PathBuf};

/// Arbitrary absolute origin for the PSG start (2020-01-01T00:00:00Z); only differences matter.
const BASE_MS: i64 = 1_577_836_800_000;
const EPOCH_S: f64 = 30.0;
const MAX_HR_GAP_S: f64 = 15.0;

#[derive(Serialize)]
#[serde(tag = "type")]
enum Sample {
    #[serde(rename = "hr")]
    Hr { t: i64, bpm: f64, quality: &'static str },
    #[serde(rename = "mot")]
    Mot {
        t: i64,
        #[serde(rename = "activityMg")]
        activity_mg: f64,
        #[serde(rename = "angleChangeDeg")]
        angle_change_deg: f64,
    },
}

#[derive(Serialize)]
struct ReplayInput {
    #[serde(rename = "sessionStartMs")]
    session_start_ms: i64,
    samples: Vec<Sample>,
}

#[derive(Serialize, Default)]
struct SubjectStats {
    id: String,
    psg_hours: f64,
    epochs: usize,
    stage_counts: [usize; 7], // index 0..5 = stage, 6 = unscored
    hr_raw_samples: usize,
    hr_median_gap_s: f64,
    hr_seconds_covered_pct: f64,
    motion_readings: usize,
    motion_hz: f64,
    motion_seconds_covered_pct: f64,
}

/// Numbers separated by commas and/or whitespace, one record per line.
fn parse_rows(path: &Path, cols: usize) -> Result<Vec<Vec<f64>>> {
    let text = fs::read_to_string(path).with_context(|| format!("reading {}", path.display()))?;
    let mut rows = Vec::with_capacity(text.len() / 24);
    for (n, line) in text.lines().enumerate() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let vals: Vec<f64> = line
            .split(|c: char| c == ',' || c.is_whitespace())
            .filter(|s| !s.is_empty())
            .map(str::parse)
            .collect::<std::result::Result<_, _>>()
            .with_context(|| format!("{}:{}: {line}", path.display(), n + 1))?;
        if vals.len() < cols {
            bail!("{}:{}: expected {cols} columns", path.display(), n + 1);
        }
        rows.push(vals);
    }
    Ok(rows)
}

/// Port of wear/.../MotionSummarizer.kt: one summary per whole second.
struct MotionSummarizer {
    second: Option<i64>,
    sum_dev: f64,
    sum_angle: f64,
    n: u32,
    last_angle: Option<f64>,
}

impl MotionSummarizer {
    fn new() -> Self {
        Self { second: None, sum_dev: 0.0, sum_angle: 0.0, n: 0, last_angle: None }
    }

    /// x, y, z in g (the Walch files are in g; the watch converts m/s^2 by dividing by 9.80665).
    fn add(&mut self, sec: i64, x: f64, y: f64, z: f64) -> Option<(i64, f64, f64)> {
        let mut out = None;
        if self.second != Some(sec) {
            out = self.close();
            self.second = Some(sec);
        }
        let g = (x * x + y * y + z * z).sqrt();
        self.sum_dev += (g - 1.0).abs();
        self.sum_angle += z.atan2((x * x + y * y).sqrt()).to_degrees();
        self.n += 1;
        out
    }

    fn close(&mut self) -> Option<(i64, f64, f64)> {
        let sec = self.second?;
        if self.n == 0 {
            return None;
        }
        let angle = self.sum_angle / self.n as f64;
        let change = self.last_angle.map_or(0.0, |a| (angle - a).abs());
        self.last_angle = Some(angle);
        let activity_mg = self.sum_dev / self.n as f64 * 1000.0;
        self.sum_dev = 0.0;
        self.sum_angle = 0.0;
        self.n = 0;
        Some((sec, activity_mg, change))
    }
}

fn round2(v: f64) -> f64 {
    (v * 100.0).round() / 100.0
}

fn process(root: &Path, out: &Path, id: &str) -> Result<SubjectStats> {
    let labels = parse_rows(&root.join(format!("labels/{id}_labeled_sleep.txt")), 2)?;
    let mut labels: Vec<(f64, i32)> = labels.iter().map(|r| (r[0], r[1] as i32)).collect();
    labels.sort_by(|a, b| a.0.total_cmp(&b.0));
    let t0 = labels.first().context("no labels")?.0;
    let end = labels.last().unwrap().0 + EPOCH_S;
    let n_sec = (end - t0).ceil() as i64;

    let mut stats = SubjectStats { id: id.to_string(), psg_hours: (end - t0) / 3600.0, ..Default::default() };

    let mut csv = String::from("epoch,stage\n");
    for &(t, stage) in &labels {
        let epoch = ((t - t0) / EPOCH_S).round() as i64;
        csv.push_str(&format!("{epoch},{stage}\n"));
        stats.epochs += 1;
        stats.stage_counts[if (0..=5).contains(&stage) { stage as usize } else { 6 }] += 1;
    }

    // Heart rate: crop to the PSG window (with one gap of margin on each side), 1 Hz grid.
    let mut hr: Vec<(f64, f64)> = parse_rows(&root.join(format!("heart_rate/{id}_heartrate.txt")), 2)?
        .into_iter()
        .map(|r| (r[0], r[1]))
        .filter(|&(t, bpm)| t >= t0 - MAX_HR_GAP_S && t <= end + MAX_HR_GAP_S && bpm > 0.0)
        .collect();
    hr.sort_by(|a, b| a.0.total_cmp(&b.0));
    hr.dedup_by(|a, b| a.0 == b.0);
    stats.hr_raw_samples = hr.iter().filter(|&&(t, _)| t >= t0 && t < end).count();
    let mut gaps: Vec<f64> = hr.windows(2).map(|w| w[1].0 - w[0].0).collect();
    gaps.sort_by(f64::total_cmp);
    stats.hr_median_gap_s = gaps.get(gaps.len() / 2).copied().unwrap_or(f64::NAN);

    let mut samples = Vec::with_capacity(n_sec as usize * 2);
    let mut j = 0;
    let mut hr_seconds = 0;
    for s in 0..n_sec {
        let t = t0 + s as f64;
        while j + 1 < hr.len() && hr[j + 1].0 <= t {
            j += 1;
        }
        if hr.len() >= 2 && j + 1 < hr.len() && hr[j].0 <= t {
            let (ta, a) = hr[j];
            let (tb, b) = hr[j + 1];
            if tb - ta <= MAX_HR_GAP_S {
                let bpm = a + (b - a) * (t - ta) / (tb - ta);
                samples.push(Sample::Hr { t: BASE_MS + s * 1000, bpm: round2(bpm), quality: "GOOD" });
                hr_seconds += 1;
            }
        }
    }
    stats.hr_seconds_covered_pct = 100.0 * hr_seconds as f64 / n_sec as f64;

    // Motion: crop and summarise per second exactly like the watch.
    let motion = parse_rows(&root.join(format!("motion/{id}_acceleration.txt")), 4)?;
    let mut summ = MotionSummarizer::new();
    let mut motion_seconds = 0;
    let mut push = |m: Option<(i64, f64, f64)>, samples: &mut Vec<Sample>| {
        if let Some((sec, act, ang)) = m {
            samples.push(Sample::Mot { t: BASE_MS + sec * 1000, activity_mg: round2(act), angle_change_deg: round2(ang) });
            motion_seconds += 1;
        }
    };
    let mut readings = 0usize;
    for r in &motion {
        let t = r[0];
        if t < t0 || t >= end {
            continue;
        }
        readings += 1;
        let m = summ.add((t - t0).floor() as i64, r[1], r[2], r[3]);
        push(m, &mut samples);
    }
    let last = summ.close();
    push(last, &mut samples);
    stats.motion_readings = readings;
    stats.motion_hz = readings as f64 / (end - t0);
    stats.motion_seconds_covered_pct = 100.0 * motion_seconds as f64 / n_sec as f64;

    samples.sort_by_key(|s| match s {
        Sample::Hr { t, .. } | Sample::Mot { t, .. } => *t,
    });
    let input = ReplayInput { session_start_ms: BASE_MS, samples };
    fs::write(out.join(format!("{id}.json")), serde_json::to_vec(&input)?)?;
    fs::write(out.join(format!("{id}.labels.csv")), csv)?;
    Ok(stats)
}

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 {
        bail!("usage: walch-prep <dataset_dir> <out_dir>");
    }
    let root = PathBuf::from(&args[1]);
    let out = PathBuf::from(&args[2]);
    fs::create_dir_all(&out)?;

    let mut ids: Vec<String> = fs::read_dir(root.join("labels"))?
        .filter_map(|e| e.ok()?.file_name().into_string().ok())
        .filter_map(|n| n.strip_suffix("_labeled_sleep.txt").map(str::to_string))
        .collect();
    ids.sort();

    let results: Vec<_> = ids.par_iter().map(|id| (id.clone(), process(&root, &out, id))).collect();
    let mut stats = Vec::new();
    for (id, r) in results {
        match r {
            Ok(s) => {
                eprintln!(
                    "{id}: {:.1} h PSG, {} epochs, HR every {:.1} s ({:.0}% of seconds), motion {:.0} Hz ({:.0}%)",
                    s.psg_hours, s.epochs, s.hr_median_gap_s, s.hr_seconds_covered_pct, s.motion_hz, s.motion_seconds_covered_pct
                );
                stats.push(s);
            }
            Err(e) => eprintln!("{id}: skipped: {e:#}"),
        }
    }
    fs::write(out.join("summary.json"), serde_json::to_vec_pretty(&stats)?)?;
    eprintln!("{} of {} subjects written to {}", stats.len(), ids.len(), out.display());
    Ok(())
}
