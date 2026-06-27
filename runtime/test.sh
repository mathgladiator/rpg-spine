#!/usr/bin/env bash
# Codegen + runtime C test loop. This is how the C side of the compiler is tested.
#
#   1. run the compiler CLI over demo/ -> demo/gen/{spine.gen.*,spine_runtime.*}
#   2. compile + run the runtime unit tests (spine_runtime_test.c)
#   3. compile + run the GENERATED load/save round-trip (spine_gen_roundtrip_test.c)
#
# Assumes spine.jar is already built (run `just build` first). Strict flags match
# hero's C build so generated code that compiles here compiles there.

set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RT="$ROOT/runtime"
GEN="$ROOT/demo/gen"
JAR="$ROOT/spine.jar"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

CC="${CC:-gcc}"
CFLAGS=(-std=c99 -Wall -Wextra -Wshadow -Wpedantic -Wno-unused-parameter)

if [[ ! -f "$JAR" ]]; then
    echo "test.sh: $JAR missing — run 'just build' first" >&2
    exit 1
fi

echo "==> codegen over demo/"
java -jar "$JAR" --compile "$ROOT/demo" --out gen --assets assets

echo "==> runtime unit tests"
"$CC" "${CFLAGS[@]}" "$RT/spine_runtime.c" "$RT/spine_runtime_test.c" -o "$TMP/rt"
"$TMP/rt"

echo "==> generated load/save round-trip (demo schema)"
"$CC" "${CFLAGS[@]}" -I "$GEN" \
    "$GEN/spine.gen.c" "$GEN/spine_runtime.c" "$RT/spine_gen_roundtrip_test.c" \
    -o "$TMP/gen"
"$TMP/gen"

echo "==> bwa codec unit tests"
"$CC" "${CFLAGS[@]}" "$RT/bwa.c" "$RT/spine_runtime.c" "$RT/bwa_test.c" -o "$TMP/bwa"
"$TMP/bwa"

echo "==> storybin round-trip (demo vault.story -> .storybin -> decode)"
"$CC" "${CFLAGS[@]}" "$RT/storybin.c" "$RT/bwa.c" "$RT/spine_runtime.c" \
    "$RT/storybin_roundtrip_test.c" -o "$TMP/storybin"
"$TMP/storybin" "$ROOT/demo/assets/stories/vault.storybin"

echo "==> all C codegen tests passed"
