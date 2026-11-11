#!/usr/bin/env bash
set -euo pipefail
gradle_lib=${1:?Pass the Gradle lib directory containing its embedded Kotlin compiler}
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
dep_cp="$test_dir"
for jar in "$gradle_lib"/kotlin-stdlib-*.jar "$gradle_lib"/guava-*.jar "$gradle_lib"/failureaccess-*.jar; do
  if [[ -f "$jar" ]]; then dep_cp="$dep_cp:$jar"; fi
done
java -m jdk.compiler/com.sun.tools.javac.Main -d "$test_dir" \
  $(find tests/control/stubs -name '*.java') \
  app/src/main/java/io/github/jqssun/airplay/audio/DacpControlState.java
java -cp "$gradle_lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -classpath "$dep_cp" -d "$test_dir" \
  app/src/main/kotlin/io/github/jqssun/airplay/audio/DacpController.kt
java -m jdk.compiler/com.sun.tools.javac.Main --add-modules jdk.httpserver \
  -cp "$dep_cp" -d "$test_dir" tests/control/DacpControllerIntegrationTest.java
java --add-modules jdk.httpserver -cp "$test_dir:$gradle_lib/*" DacpControllerIntegrationTest
