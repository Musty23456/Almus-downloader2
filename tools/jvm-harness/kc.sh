#!/bin/bash
# usage: kc.sh <out-dir> <classpath-extra> <sources...>
L=${KOTLIN_LIB:-/opt/gradle-8.14.3/lib}
KCP=$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$(ls $L/trove4j*.jar $L/annotations-*.jar 2>/dev/null | tr '\n' ':')
OUT=$1; CP=$2; shift 2
java -Xmx2g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -nowarn -d "$OUT" -cp "$CP" "$@"
