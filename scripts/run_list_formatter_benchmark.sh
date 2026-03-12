#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JBR_PATH="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
REPORT_PATH="$ROOT_DIR/app/build/reports/formatter/list-benchmark.txt"

if [[ -z "${JAVA_HOME:-}" ]] && [[ -x "$JBR_PATH/bin/java" ]]; then
  export JAVA_HOME="$JBR_PATH"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! command -v java >/dev/null 2>&1; then
  echo "No Java runtime found. Install Java or Android Studio." >&2
  exit 1
fi

cd "$ROOT_DIR"

./gradlew testDebugUnitTest \
  --tests "com.aaryaharkare.voicekeyboard.formatter.*" \
  --console=plain

echo
echo "Benchmark report:"
echo "$REPORT_PATH"
echo
cat "$REPORT_PATH"
