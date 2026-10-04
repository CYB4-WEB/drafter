#!/usr/bin/env bash
# Compiles the pure OneNote parser core (no Android imports) with the Kotlin compiler bundled in the Gradle
# distribution and runs OneDump on the given files. Usage: docs/testdata/onenote/run_parser.sh file.one [file.onepkg …]
set -e
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
LIB=$(ls -d /root/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/lib | head -1)
CP="$LIB/kotlin-compiler-embeddable-2.0.21.jar:$LIB/kotlin-stdlib-2.0.21.jar:$LIB/kotlin-reflect-2.0.21.jar:$LIB/kotlinx-coroutines-core-jvm-1.6.4.jar:$LIB/trove4j-1.0.20200330.jar:$LIB/annotations-24.0.1.jar"
OUT="${TMPDIR:-/tmp}/onedump-classes"
SRC="$ROOT/app/src/main/java/com/daftar/app/onenote"
mkdir -p "$OUT"
NEWEST=$(ls -t "$SRC"/OneModel.kt "$SRC"/OneStore.kt "$SRC"/OneDoc.kt "$SRC"/Cab.kt "$SRC"/OneLoader.kt "$ROOT/docs/testdata/onenote/OneDump.kt" | head -1)
if [ ! -f "$OUT/.stamp" ] || [ "$NEWEST" -nt "$OUT/.stamp" ]; then
  java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$LIB/kotlin-stdlib-2.0.21.jar" -d "$OUT" \
    "$SRC"/OneModel.kt "$SRC"/OneStore.kt "$SRC"/OneDoc.kt "$SRC"/Cab.kt "$SRC"/OneLoader.kt "$ROOT/docs/testdata/onenote/OneDump.kt" 2>&1 | grep -v "JAVA_TOOL_OPTIONS" | grep -v "^warning:" || true
  touch "$OUT/.stamp"
fi
java -cp "$OUT:$LIB/kotlin-stdlib-2.0.21.jar" OneDumpKt "$@" 2>&1 | grep -v JAVA_TOOL_OPTIONS
