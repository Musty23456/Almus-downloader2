#!/bin/bash
# usage: test.sh <out-dir> [TestClass...]  (classes relative to com.almus.ytdownloader.engine)
OUT=$1; shift; L=${KOTLIN_LIB:-/opt/gradle-8.14.3/lib}
CLASSES=""
if [ $# -eq 0 ]; then for f in $(cd $OUT/test && ls com/almus/ytdownloader/engine/*Test.class); do CLASSES="$CLASSES $(echo ${f%.class} | tr / .)"; done
else for c in "$@"; do CLASSES="$CLASSES com.almus.ytdownloader.engine.$c"; done; fi
java -cp $OUT/main:$OUT/test:$L/kotlin-stdlib-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v JAVA_TOOL
