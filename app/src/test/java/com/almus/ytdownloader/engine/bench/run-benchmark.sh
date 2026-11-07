#!/bin/bash
# Compares the ORIGINAL engine with the NEW one on the same scenarios.
# usage: run-benchmark.sh <original-project-root> <new-project-root> [runs] [outdir]
# Needs a JDK and the Kotlin compiler + coroutines + junit jars (see BENCHMARKS.md); paths are set below.
set -e
ORIG=$1; NEW=$2; RUNS=${3:-3}; OUT=${4:-benchmark-out}
KOTLIN_LIB=${KOTLIN_LIB:-/opt/gradle-8.14.3/lib}   # kotlin-compiler-embeddable, kotlin-stdlib, coroutines-core-jvm, junit
mkdir -p "$OUT"
for which in original new; do
  ROOT=$([ $which = original ] && echo "$ORIG" || echo "$NEW")
  BENCH_SRC="$NEW/app/src/test/java/com/almus/ytdownloader/engine"
  EXTRA_TEST="$BENCH_SRC/RangeFileServer.kt $BENCH_SRC/bench/DownloadBenchmark.kt" "$NEW/tools/jvm-harness/build.sh" "$ROOT" "$OUT/build-$which" >/dev/null
  CP="$OUT/build-$which/main:$OUT/build-$which/test:$KOTLIN_LIB/kotlin-stdlib-2.0.21.jar:$KOTLIN_LIB/kotlinx-coroutines-core-jvm-1.6.4.jar:$KOTLIN_LIB/junit-4.13.2.jar"
  java -cp "$CP" com.almus.ytdownloader.engine.bench.DownloadBenchmark "$RUNS" 2>/dev/null | grep '^{' > "$OUT/$which.jsonl"
done
echo "results in $OUT/original.jsonl and $OUT/new.jsonl"
