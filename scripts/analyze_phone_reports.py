#!/usr/bin/env python3
"""Analyze Michelina Focus session exports without trusting the in-app summary."""

from __future__ import annotations

import argparse
import csv
import html
import json
import math
import wave
from pathlib import Path
from statistics import mean

import numpy as np


MIN_DB = -120.0


def dbfs(amplitude: float) -> float:
    if amplitude <= 1e-6:
        return MIN_DB
    return max(MIN_DB, 20.0 * math.log10(amplitude))


def wav_stats(path: Path) -> dict[str, float | int]:
    with wave.open(str(path), "rb") as wav:
        channels = wav.getnchannels()
        sample_rate = wav.getframerate()
        sample_width = wav.getsampwidth()
        frame_count = wav.getnframes()
        raw = wav.readframes(frame_count)

    if channels != 1 or sample_width != 2:
        raise ValueError(f"Unsupported WAV {path}: {channels}ch/{sample_width * 8}bit")
    samples = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    absolute = np.abs(samples)
    rms = float(np.sqrt(np.mean(np.square(samples, dtype=np.float64)))) if samples.size else 0.0
    peak = float(np.max(absolute)) if samples.size else 0.0

    window_size = max(1, sample_rate // 10)
    window_count = samples.size // window_size
    if window_count:
        windows = samples[: window_count * window_size].reshape(window_count, window_size)
        window_rms = np.sqrt(np.mean(np.square(windows, dtype=np.float64), axis=1))
        window_db = np.maximum(MIN_DB, 20.0 * np.log10(np.maximum(window_rms, 1e-6)))
        p50 = float(np.percentile(window_db, 50))
        p90 = float(np.percentile(window_db, 90))
    else:
        p50 = MIN_DB
        p90 = MIN_DB

    return {
        "sample_rate": sample_rate,
        "samples": int(samples.size),
        "duration_s": samples.size / sample_rate,
        "rms_dbfs": dbfs(rms),
        "peak_dbfs": dbfs(peak),
        "window_p50_dbfs": p50,
        "window_p90_dbfs": p90,
        "zero_percent": float(np.mean(absolute == 0.0) * 100.0),
        "below_minus_60_percent": float(np.mean(absolute < 10 ** (-60.0 / 20.0)) * 100.0),
        "clipped_percent": float(np.mean(absolute >= 0.919) * 100.0),
    }


def numeric(rows: list[dict[str, str]], key: str) -> list[float]:
    values: list[float] = []
    for row in rows:
        try:
            values.append(float(row.get(key, "")))
        except (TypeError, ValueError):
            pass
    return values


def initial_settings(events_path: Path) -> dict[str, str]:
    with events_path.open(newline="", encoding="utf-8") as handle:
        for event in csv.DictReader(handle):
            if event.get("event") != "settings_start":
                continue
            tokens = event.get("value", "").split(";")
            settings = {"mode": tokens[0] if tokens else "—"}
            for token in tokens[1:]:
                key, separator, value = token.partition("=")
                if separator:
                    settings[key] = value
            return settings
    return {}


def analyze_session(session_dir: Path) -> dict[str, object]:
    summary = json.loads((session_dir / "summary.json").read_text(encoding="utf-8"))
    with (session_dir / "metrics.csv").open(newline="", encoding="utf-8") as handle:
        metrics = list(csv.DictReader(handle))
    settings = initial_settings(session_dir / "events.csv")
    input_audio = wav_stats(session_dir / "input_raw.wav")
    output_audio = wav_stats(session_dir / "output_processed.wav")

    modes = [row.get("mode", "") for row in metrics]
    voice_focus_percent = 100.0 * modes.count("VOICE_FOCUS") / len(modes) if modes else 0.0
    underruns = numeric(metrics, "underruns")
    dsp_peak = numeric(metrics, "dsp_peak_ms")
    signal_change = numeric(metrics, "signal_changed_percent")
    denoise_delta = numeric(metrics, "denoise_delta_db")
    vad_inference = numeric(metrics, "vad_inference_ms")
    audio_delta = float(output_audio["rms_dbfs"]) - float(input_audio["rms_dbfs"])
    summary_duration_s = float(summary.get("durationMs", 0)) / 1000.0
    audio_duration_s = float(input_audio["duration_s"])
    duration_error_percent = (
        100.0 * (summary_duration_s - audio_duration_s) / summary_duration_s
        if summary_duration_s
        else 0.0
    )
    hop_ms = 1000.0 * int(summary["audioFrames"] and input_audio["samples"]) / (
        max(1, int(summary["audioFrames"])) * int(summary["sampleRateHz"])
    )

    flags: list[str] = []
    if audio_delta < -10.0 and float(summary.get("speechDetectedPercent", 0)) >= 20.0:
        flags.append("OUTPUT_LOW")
    underrun_delta = int(max(0.0, underruns[-1] - underruns[0])) if underruns else 0
    if duration_error_percent > 1.0:
        flags.append("AUDIO_FELL_BEHIND")
    if underrun_delta > 0:
        flags.append("XRUN_INCREASE")
    if float(summary.get("averageProcessingMs", 0)) > hop_ms * 0.8:
        flags.append("LOW_DSP_HEADROOM")
    if float(output_audio["clipped_percent"]) > 0.1:
        flags.append("CLIPPING")

    return {
        "id": summary["id"],
        "backend": summary["backend"],
        "sample_rate_hz": summary["sampleRateHz"],
        "output_route": summary["outputRoute"],
        "duration_s": summary_duration_s,
        "audio_duration_s": audio_duration_s,
        "duration_error_percent": duration_error_percent,
        "input_rms_dbfs": input_audio["rms_dbfs"],
        "output_rms_dbfs": output_audio["rms_dbfs"],
        "audio_delta_db": audio_delta,
        "input_peak_dbfs": input_audio["peak_dbfs"],
        "output_peak_dbfs": output_audio["peak_dbfs"],
        "output_p50_dbfs": output_audio["window_p50_dbfs"],
        "output_p90_dbfs": output_audio["window_p90_dbfs"],
        "output_below_minus_60_percent": output_audio["below_minus_60_percent"],
        "output_zero_percent": output_audio["zero_percent"],
        "output_clipped_percent": output_audio["clipped_percent"],
        "speech_detected_percent": summary.get("speechDetectedPercent", 0),
        "vad_inference_ms": mean(vad_inference) if vad_inference else 0.0,
        "dsp_average_ms": summary.get("averageProcessingMs", 0),
        "dsp_peak_ms": max(dsp_peak) if dsp_peak else summary.get("peakProcessingMs", 0),
        "hop_ms": hop_ms,
        "dsp_utilization_percent": 100.0 * float(summary.get("averageProcessingMs", 0)) / hop_ms,
        "realtime_audio_percent": 100.0 * audio_duration_s / summary_duration_s if summary_duration_s else 0.0,
        "starting_underruns": int(underruns[0]) if underruns else 0,
        "max_underruns": int(max(underruns)) if underruns else 0,
        "underrun_delta": underrun_delta,
        "voice_focus_percent": voice_focus_percent,
        "denoise_delta_db": mean(denoise_delta) if denoise_delta else 0.0,
        "signal_change_percent": mean(signal_change) if signal_change else 0.0,
        "gain_db": float(settings.get("gain", 0)),
        "denoise_set": float(settings.get("denoise", 0)),
        "presence_set": float(settings.get("presence", 0)),
        "weak_db": float(settings.get("weak", 0)),
        "initial_mode": settings.get("mode", "—"),
        "setting_changes": summary.get("settingChanges", 0),
        "understood": summary.get("understood", 0),
        "missed": summary.get("missed", 0),
        "flags": ",".join(flags) if flags else "OK",
    }


def fmt(value: object, digits: int = 1) -> str:
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def write_csv(rows: list[dict[str, object]], destination: Path) -> None:
    with destination.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def write_markdown(rows: list[dict[str, object]], destination: Path, source: Path) -> None:
    flagged = [row for row in rows if row["flags"] != "OK"]
    total_seconds = sum(float(row["audio_duration_s"]) for row in rows)
    lines = [
        "# Pixel 8 Pro session analysis",
        "",
        f"Source: `{source}`",
        "",
        f"Sessions: **{len(rows)}** · analyzed audio: **{total_seconds / 60:.1f} min** · flagged: **{len(flagged)}**",
        "",
        "| Session | Model | Output | Duration | IN RMS | OUT RMS | Actual Δ | Speech | DSP avg/max | Flag |",
        "|---|---|---|---:|---:|---:|---:|---:|---:|---|",
    ]
    for row in rows:
        route = str(row["output_route"]).split(" · ")[0]
        lines.append(
            f"| {row['id']} | {row['backend']} | {route} | {float(row['audio_duration_s']):.1f}s | "
            f"{float(row['input_rms_dbfs']):.1f} | {float(row['output_rms_dbfs']):.1f} | "
            f"{float(row['audio_delta_db']):+.1f} dB | {float(row['speech_detected_percent']):.0f}% | "
            f"{float(row['dsp_average_ms']):.1f}/{float(row['dsp_peak_ms']):.1f} ms | {row['flags']} |"
        )
    lines.extend(
        [
            "",
            "## Automated checks",
            "",
            "- `OUTPUT_LOW`: output is more than 10 dB below input while the VAD detected speech in at least 20% of the session.",
            "- `LOW_DSP_HEADROOM`: average DSP time exceeds 80% of the audio-block duration.",
            "- `XRUN_INCREASE`: the Android underrun counter increased during the session; the initial value is subtracted.",
            "- `AUDIO_FELL_BEHIND`: the WAV contains over 1% less audio than the session's wall-clock duration.",
            "- RMS levels are recalculated directly from the PCM WAV files and do not depend on the app-generated summary.",
            "",
        ]
    )
    destination.write_text("\n".join(lines), encoding="utf-8")


def bar(value: float, low: float = -60.0, high: float = 15.0) -> str:
    width = max(0.0, min(100.0, (value - low) / (high - low) * 100.0))
    color = "#ff5d73" if value < -10.0 else "#5cd6ff"
    return f'<span class="bar"><i style="width:{width:.1f}%;background:{color}"></i></span>'


def write_html(rows: list[dict[str, object]], destination: Path) -> None:
    table_rows: list[str] = []
    for row in rows:
        delta = float(row["audio_delta_db"])
        table_rows.append(
            "<tr>"
            f"<td>{html.escape(str(row['id']))}</td>"
            f"<td>{html.escape(str(row['backend']))}</td>"
            f"<td>{html.escape(str(row['output_route']).split(' · ')[0])}</td>"
            f"<td>{float(row['audio_duration_s']) / 60:.1f}m</td>"
            f"<td>{float(row['input_rms_dbfs']):.1f}</td>"
            f"<td>{float(row['output_rms_dbfs']):.1f}</td>"
            f"<td class='delta'>{delta:+.1f} dB {bar(delta)}</td>"
            f"<td>{float(row['speech_detected_percent']):.0f}%</td>"
            f"<td>{float(row['dsp_average_ms']):.1f}/{float(row['dsp_peak_ms']):.1f}</td>"
            f"<td>{html.escape(str(row['flags']))}</td>"
            "</tr>"
        )
    document = f"""<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Michelina · Report Pixel</title>
<style>
body{{margin:0;background:#080808;color:#eee;font:14px ui-monospace,SFMono-Regular,monospace}}
main{{max-width:1500px;margin:auto;padding:28px}} h1{{font:700 26px system-ui;margin:0 0 8px}}
p{{color:#999}} table{{width:100%;border-collapse:collapse;margin-top:24px}}
th,td{{padding:10px;border-bottom:1px solid #292929;text-align:left;white-space:nowrap}}
th{{color:#888;font-size:11px}} .delta{{min-width:180px}} .bar{{display:block;width:160px;height:4px;background:#222;margin-top:6px}}
.bar i{{display:block;height:100%}} code{{color:#5cd6ff}} @media(max-width:900px){{main{{overflow:auto}}}}
</style><main><h1>MICHELINA · PIXEL REPORT</h1>
<p>{len(rows)} sessions · levels recalculated directly from WAV files · red = attenuation greater than −10 dB</p>
<table><thead><tr><th>SESSION</th><th>MODEL</th><th>OUTPUT</th><th>AUDIO</th><th>IN</th><th>OUT</th><th>ACTUAL DELTA</th><th>SPEECH</th><th>DSP AVG/MAX</th><th>FLAG</th></tr></thead>
<tbody>{''.join(table_rows)}</tbody></table></main></html>"""
    destination.write_text(document, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    source = args.source.resolve()
    output = (args.output or source / "analysis").resolve()
    output.mkdir(parents=True, exist_ok=True)

    sessions = sorted(path.parent for path in source.glob("*/summary.json"))
    if not sessions:
        raise SystemExit(f"No sessions found in {source}")
    rows = [analyze_session(session) for session in sessions]
    write_csv(rows, output / "sessions.csv")
    write_markdown(rows, output / "ANALYSIS.md", source)
    write_html(rows, output / "report.html")
    print(f"Analyzed {len(rows)} sessions in {output}")


if __name__ == "__main__":
    main()
