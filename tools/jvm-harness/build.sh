#!/bin/bash
# usage: build.sh <project-root> <out-dir>   — compiles main (JVM-pure parts) + engine tests
set -e
ROOT=$1; OUT=$2; H=$(cd "$(dirname "$0")" && pwd); L=${KOTLIN_LIB:-/opt/gradle-8.14.3/lib}
M=$ROOT/app/src/main/java/com/almus/ytdownloader; T=$ROOT/app/src/test/java/com/almus/ytdownloader
rm -rf $OUT; mkdir -p $OUT/main $OUT/test
BASECP=$L/kotlin-stdlib-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar
MAIN=$(ls $M/engine/*.kt | grep -v OkHttpTransport.kt) 
MAIN="$MAIN $(ls $M/download/*.kt | grep -v -E 'Android|Receiver|Permissions|SharedPrefs|DownloadRepository|\/Platform|SnapshotReducer|SaveLocation')"
MAIN="$MAIN $M/platform/UrlSafety.kt $M/platform/ParsedUrl.kt $H/shim/JdkTransport.kt"
$H/kc.sh $OUT/main $BASECP $MAIN 2>&1 | grep -v "^warning" || true
TEST="$(ls $T/engine/*.kt) $T/download/TestHttpServer.kt"
[ -n "$EXTRA_TEST" ] && TEST="$TEST $EXTRA_TEST"
$H/kc.sh $OUT/test $BASECP:$OUT/main:$L/junit-4.13.2.jar $TEST 2>&1 | grep -v "^warning" || true
