#!/usr/bin/env bash
# 一致性校验运行器（spec/vectors → 各实现 → 中立比较）
#
# 用法：
#   scripts/conformance.sh [java|rust|all|list] [--no-build] [--vectors DIR] [--keep]
#
# 退出码：0 = 全部通过；1 = 有失败（CI 门禁即依赖此退出码）
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VECTORS="$ROOT/spec/vectors"
JAR="$ROOT/java/app/build/libs/backup.jar"
RUST_BIN="$ROOT/rust/target/release/rust-thanos"

TARGETS=()
BUILD=1
KEEP=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) BUILD=0 ;;
    --vectors) VECTORS="$2"; shift ;;
    --keep) KEEP=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) TARGETS+=("$1") ;;
  esac
  shift
done
[[ ${#TARGETS[@]} -eq 0 ]] && TARGETS=(all)

vec_params() { python3 -c 'import json,sys; p=json.load(open(sys.argv[1])); print(" ".join(p["args"]))' "$1"; }

build_java() {
  echo "▶ build java ($JAR)"
  "$ROOT/java/gradlew" -p "$ROOT/java" :app:shadowJar -q --no-daemon || return 1
}
build_rust() {
  echo "▶ build rust ($RUST_BIN)"
  cargo build --release --quiet --manifest-path "$ROOT/rust/Cargo.toml" || return 1
}

run_one() {
  local impl="$1" vec="$2" name args work out log
  name="$(basename "$vec")"
  args="$(vec_params "$vec/params.json")"
  work="$(mktemp -d "${TMPDIR:-/tmp}/conformance-${impl}-${name}-XXXX")"
  out="$work/out"
  log="$work/run.log"

  case "$impl" in
    java) ( cd "$work" && java -jar "$JAR" "$vec/input" "$out" $args ) >"$log" 2>&1 ;;
    rust) ( cd "$work" && "$RUST_BIN" backup "$vec/input" "$out" $args ) >"$log" 2>&1 ;;
    *) echo "::error::unknown impl: $impl"; return 1 ;;
  esac
  local rc=$?
  if [[ $rc -ne 0 ]]; then
    echo "[FAIL] $impl/$name: exited $rc"
    tail -20 "$log" | sed 's/^/    /'
    KEEP=1
    return 1
  fi

  python3 "$ROOT/spec/tools/manifest.py" "$out" --vector "$name" --out "$work/actual.json" >/dev/null || return 1
  if python3 "$ROOT/spec/tools/compare.py" "$vec/expected/manifest.json" "$work/actual.json"; then
    echo "[PASS] $impl/$name"
    [[ $KEEP -eq 1 ]] || rm -rf "$work"
    return 0
  fi
  echo "    (artifacts kept: $work)"
  return 1
}

run_impl() {
  local impl="$1" failures=0 total=0 vec
  echo "▶ conformance: $impl"
  for vec in "$VECTORS"/*/; do
    [[ -f "$vec/params.json" ]] || continue
    total=$((total + 1))
    run_one "$impl" "$vec" || failures=$((failures + 1))
  done
  echo "  $impl: $((total - failures))/$total passed"
  [[ $failures -eq 0 ]]
}

list_vectors() {
  for vec in "$VECTORS"/*/; do
    [[ -f "$vec/params.json" ]] && echo "$(basename "$vec")"
  done
}

status=0
for t in "${TARGETS[@]}"; do
  case "$t" in
    list) list_vectors ;;
    java)
      [[ $BUILD -eq 1 ]] && { build_java || exit 1; }
      run_impl java || status=1 ;;
    rust)
      [[ $BUILD -eq 1 ]] && { build_rust || exit 1; }
      run_impl rust || status=1 ;;
    all)
      [[ $BUILD -eq 1 ]] && { build_java && build_rust || exit 1; }
      run_impl java || status=1
      run_impl rust || status=1 ;;
    *) echo "::error::unknown target: $t"; exit 2 ;;
  esac
done
exit $status
