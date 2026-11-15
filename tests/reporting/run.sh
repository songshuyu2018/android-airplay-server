#!/usr/bin/env bash
set -euo pipefail
output=$(mktemp -d)
trap 'rm -rf "$output"' EXIT
javac -encoding UTF-8 --add-modules jdk.httpserver -d "$output" \
  app/src/main/java/io/github/jqssun/airplay/reporting/*.java \
  tests/reporting/PlaybackReportingTest.java
java --add-modules jdk.httpserver -cp "$output" PlaybackReportingTest
