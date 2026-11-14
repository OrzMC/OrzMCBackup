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

vec_field() { python3 -c 'import json,sys; p=json.load(open(sys.argv[1])); v=p.get(sys.argv[2],""); print(v if isinstance(v,str) else int(v))' "$1" "$2"; }
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
  local command expect_no_output dry_run expect_exit output_override world_field world_path report_args=""
  command="$(vec_field "$vec/params.json" command)"
  world_field="$(vec_field "$vec/params.json" world)"
  expect_exit="$(vec_field "$vec/params.json" expect_exit)"
  output_override="$(vec_field "$vec/params.json" output)"
  [[ -z "$expect_exit" ]] && expect_exit=0
  case "$output_override" in
    base) out="$work/input/base" ;;
    patch) out="$work/input/patch" ;;
    input) out="$work/input" ;;
  esac
  expect_no_output="$(vec_field "$vec/params.json" expect_no_output)"
  dry_run="$(vec_field "$vec/params.json" dry_run)"
  [[ "$expect_no_output" == "1" ]] && expect_no_output=true || expect_no_output=false
  [[ "$dry_run" == "1" ]] && dry_run=true || dry_run=false

  # run against a private copy so a vector may legitimately stress destructive paths
  # (overlap guards) without endangering the committed fixtures
  cp -a "$vec/input" "$work/input"
  world_path="$work/input"
  case "$world_field" in
    "") ;;
    symlink)
      # WOS-7.5: the world root itself is a symlink to the real directory
      ln -s "$work/input" "$work/world-link"
      world_path="$work/world-link" ;;
    *) world_path="$work/input/$world_field" ;;
  esac
  if python3 -c 'import json,sys; sys.exit(0 if "report" in json.load(open(sys.argv[1])) else 1)' "$vec/params.json"; then
    report_args="--report-file $work/report.json --report-format json"
  fi

  # input must be read-only for every vector: snapshot it before the run
  python3 "$ROOT/spec/tools/manifest.py" "$work/input" --out "$work/input-before.json" >/dev/null || return 1

  # bash 3.2 (macOS) treats "${empty[@]}" under `set -u` as unbound, so use a plain string
  local dry_args=""
  [[ "$dry_run" == true ]] && dry_args="--dry-run"

  case "$impl:$command" in
    java:backup) ( cd "$work" && java -jar "$JAR" "$world_path" "$out" $dry_args $report_args $args ) >"$log" 2>&1 ;;
    rust:backup) ( cd "$work" && "$RUST_BIN" backup "$world_path" "$out" $dry_args $report_args $args ) >"$log" 2>&1 ;;
    java:merge) ( cd "$work" && java -jar "$JAR" merge "$work/input/base" "$work/input/patch" "$out" $dry_args $report_args $args ) >"$log" 2>&1 ;;
    rust:merge) ( cd "$work" && "$RUST_BIN" merge "$work/input/base" "$work/input/patch" "$out" $dry_args $args ) >"$log" 2>&1 ;;
    *) echo "::error::unknown impl/command: $impl/$command"; return 1 ;;
  esac
  local rc=$?
  if [[ "$rc" != "$expect_exit" ]]; then
    echo "[FAIL] $impl/$name: exited $rc, expected $expect_exit"
    tail -20 "$log" | sed 's/^/    /'
    KEEP=1
    return 1
  fi
  if [[ "$expect_exit" != "0" ]]; then
    # expected failure: the run must not have produced output nor touched the input
    python3 "$ROOT/spec/tools/manifest.py" "$work/input" --out "$work/input-after.json" >/dev/null || return 1
    if ! diff -q "$work/input-before.json" "$work/input-after.json" >/dev/null; then
      echo "[FAIL] $impl/$name: input was modified by a rejected run"
      return 1
    fi
    if [[ "$expect_no_output" == true && -e "$out" ]]; then
      echo "[FAIL] $impl/$name: expected no output, but $out exists"
      return 1
    fi
    echo "[PASS] $impl/$name (rejected as expected, nothing written)"
    [[ $KEEP -eq 1 ]] || rm -rf "$work"
    return 0
  fi

  # input must be untouched by the run (WOS-9.5 / world safety)
  python3 "$ROOT/spec/tools/manifest.py" "$work/input" --out "$work/input-after.json" >/dev/null || return 1
  if ! diff -q "$work/input-before.json" "$work/input-after.json" >/dev/null; then
    echo "[FAIL] $impl/$name: input directory was modified by the run"
    return 1
  fi

  if [[ ! -f "$vec/expected/manifest.json" ]]; then
    echo "[PASS] $impl/$name (no expected output; input unchanged)"
    [[ $KEEP -eq 1 ]] || rm -rf "$work"
    return 0
  fi

  if [[ "$expect_no_output" == true ]]; then
    if [[ -e "$out" ]]; then
      echo "[FAIL] $impl/$name: expected no output, but $out exists"
      return 1
    fi
    echo "[PASS] $impl/$name (no output written)"
    [[ $KEEP -eq 1 ]] || rm -rf "$work"
    return 0
  fi

  python3 "$ROOT/spec/tools/manifest.py" "$out" --vector "$name" --out "$work/actual.json" >/dev/null || return 1
  if [[ -n "$report_args" ]]; then
    python3 -c 'import json,sys; d=json.load(open(sys.argv[1]))["report"]; json.dump(d, open(sys.argv[2],"w"))'       "$vec/params.json" "$work/expected-report.json"
    if ! python3 "$ROOT/spec/tools/check_report.py" "$work/expected-report.json" "$work/report.json"; then
      echo "[FAIL] $impl/$name: report fields differ"
      return 1
    fi
  fi
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
