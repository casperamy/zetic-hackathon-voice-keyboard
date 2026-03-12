#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JBR_PATH="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
REPORT_PATH="$ROOT_DIR/app/build/reports/formatter/list-benchmark.txt"
LOOP_DIR="$ROOT_DIR/app/build/reports/formatter"
HISTORY_PATH="$LOOP_DIR/rewrite-loop-history.jsonl"
MAX_ITERATIONS=12

if [[ "${1:-}" == "--reset" ]]; then
  rm -f "$HISTORY_PATH"
  echo "Reset rewrite loop history: $HISTORY_PATH"
  exit 0
fi

if [[ -z "${JAVA_HOME:-}" ]] && [[ -x "$JBR_PATH/bin/java" ]]; then
  export JAVA_HOME="$JBR_PATH"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! command -v java >/dev/null 2>&1; then
  echo "No Java runtime found. Install Java or Android Studio." >&2
  exit 1
fi

mkdir -p "$LOOP_DIR"

current_iteration=1
if [[ -f "$HISTORY_PATH" ]]; then
  current_iteration=$(( $(wc -l < "$HISTORY_PATH") + 1 ))
fi

if (( current_iteration > MAX_ITERATIONS )); then
  echo "Rewrite loop limit reached ($MAX_ITERATIONS). Reset with --reset after reviewing the benchmark." >&2
  exit 3
fi

"$ROOT_DIR/scripts/run_list_formatter_benchmark.sh"

python3 - <<'PY' "$REPORT_PATH" "$HISTORY_PATH" "$current_iteration" "$MAX_ITERATIONS"
import json
import re
import sys
from pathlib import Path

report_path = Path(sys.argv[1])
history_path = Path(sys.argv[2])
iteration = int(sys.argv[3])
max_iterations = int(sys.argv[4])
text = report_path.read_text()

def section(name: str) -> str:
    pattern = rf"(?ms)^\s*dataset={name}\s*$\n(.*?)(?=^\s*(?:dataset=|combined\s*$)|\Z)"
    match = re.search(pattern, text, re.S)
    if not match:
        raise SystemExit(f"Missing dataset section: {name}")
    return match.group(1)

core = section("core")
regressions = section("regressions")
combined_match = re.search(r"(?ms)^\s*combined\s*$\n(.*)$", text)
if not combined_match:
    raise SystemExit("Missing combined section")
combined = combined_match.group(1)

def metric(block: str, key: str) -> int:
    match = re.search(rf"{re.escape(key)}=(\d+)%?", block)
    if not match:
        raise SystemExit(f"Missing metric {key}")
    return int(match.group(1))

def count(block: str, key: str) -> int:
    match = re.search(rf"{re.escape(key)}=(\d+)", block)
    if not match:
        raise SystemExit(f"Missing count {key}")
    return int(match.group(1))

entry = {
    "iteration": iteration,
    "core_precision": metric(core, "precision"),
    "core_recall": metric(core, "recall"),
    "core_exact": metric(core, "exact_positive_bullet_count_accuracy"),
    "core_fp": count(core, "fp"),
    "core_fn": count(core, "fn"),
    "regression_fp": count(regressions, "fp"),
    "regression_fn": count(regressions, "fn"),
    "combined_precision": metric(combined, "precision"),
    "combined_recall": metric(combined, "recall"),
    "combined_exact": metric(combined, "exact_positive_bullet_count_accuracy"),
}

with history_path.open("a", encoding="utf-8") as fh:
    fh.write(json.dumps(entry) + "\n")

history = [json.loads(line) for line in history_path.read_text().splitlines() if line.strip()]
success = (
    entry["core_precision"] >= 95
    and entry["core_recall"] >= 95
    and entry["core_exact"] >= 90
    and entry["regression_fp"] == 0
    and entry["regression_fn"] == 0
)

def improved(curr, prev):
    return (
        curr["core_precision"] > prev["core_precision"]
        or curr["core_recall"] > prev["core_recall"]
        or curr["core_exact"] > prev["core_exact"]
        or (curr["core_fp"] + curr["core_fn"]) < (prev["core_fp"] + prev["core_fn"])
    )

stagnant = False
if len(history) >= 3:
    stagnant = (not improved(history[-1], history[-2])) and (not improved(history[-2], history[-3]))

print()
print(f"Rewrite loop iteration {iteration}/{max_iterations}")
print(json.dumps(entry, indent=2))

if success:
    print("Thresholds met and regressions are clean.")
    raise SystemExit(0)
if stagnant:
    print("No primary-metric or mismatch-count improvement across two consecutive iterations. Review the benchmark before continuing.", file=sys.stderr)
    raise SystemExit(2)

print("Thresholds not met yet. Edit deterministic rules and rerun this script.")
raise SystemExit(1)
PY
