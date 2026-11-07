# JVM harness

Compiles the pure-Kotlin engine (`engine/` minus Android and OkHttp, plus the few `download/` and `platform/` classes it uses) and runs its JUnit tests and the benchmark on a plain JVM, without the Android SDK or Gradle downloads.

    KOTLIN_LIB=/path/with/jars tools/jvm-harness/build.sh <project-root> <out-dir>
    tools/jvm-harness/test.sh <out-dir> [TestClass ...]      # run from the project root (the source guard test reads sources)

`KOTLIN_LIB` needs kotlin-compiler-embeddable, kotlin-stdlib, kotlinx-coroutines-core-jvm, junit and hamcrest-core jars (Gradle's own `lib` folder has them; default `/opt/gradle-8.14.3/lib`). `shim/JdkTransport.kt` replaces `OkHttpTransport` here only. In the real project use `./gradlew testDebugUnitTest`.
