#!/usr/bin/env python3
"""Summarize p50 / p95 / min / max latency metrics from Shield PoC benchmarks.jsonl."""

import json
import math
import sys
from collections import defaultdict
from pathlib import Path


def percentile(values: list[float], p: float) -> float:
    if not values:
        return float("nan")
    s = sorted(values)
    k = (len(s) - 1) * (p / 100.0)
    lo = math.floor(k)
    hi = math.ceil(k)
    if lo == hi:
        return s[int(k)]
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python scripts/analyze_benchmarks.py <path/to/benchmarks.jsonl>")
        sys.exit(1)

    path = Path(sys.argv[1])
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows:
        print("No benchmark records found.")
        sys.exit(0)

    by_mode: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        key = f"{row.get('mode', 'UNKNOWN')} (sw={row.get('softwareDecoder', False)})"
        by_mode[key].append(row)

    metrics = [
        ("relayToShieldMs", "Relay -> Shield WS"),
        ("iceGatherMs", "ICE Gather"),
        ("whepRttMs", "WHEP Offer/Answer"),
        ("ringServerRttMs", "  └─ Ring API RTT"),
        ("iceConnectMs", "Answer -> ICE Connected"),
        ("firstFrameAfterConnectMs", "Connected -> First Frame"),
        ("totalShieldMs", "TOTAL Shield (Rx -> Visible)"),
    ]

    for group, items in by_mode.items():
        ok = [r for r in items if r.get("success")]
        print(f"\n=== {group} | n={len(items)} (success={len(ok)}/{len(items)}, {100.0 * len(ok) / len(items):.1f}%) ===")
        print(f"{'Stage':<30} {'p50 (ms)':>10} {'p95 (ms)':>10} {'min':>8} {'max':>8}")
        print("-" * 70)
        for field, label in metrics:
            vals = [float(r[field]) for r in ok if r.get(field) is not None]
            if not vals:
                continue
            print(
                f"{label:<30} {percentile(vals, 50):>10.1f} {percentile(vals, 95):>10.1f} "
                f"{min(vals):>8.1f} {max(vals):>8.1f}"
            )


if __name__ == "__main__":
    main()
