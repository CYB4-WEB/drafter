#!/usr/bin/env bash
# Serialized compile for parallel agents: only one Gradle build runs at a time (shared daemon, low RAM).
# Usage: tools/compile.sh            -> :app:compileDebugKotlin
#        tools/compile.sh <tasks...> -> any gradle tasks (e.g. :app:assembleDebug)
# Prints only errors/warnings; last line is BUILD OK or BUILD FAILED.
cd "$(dirname "$0")/.." || exit 1
[ -f local.properties ] || echo "sdk.dir=/opt/android-sdk" > local.properties
TASKS=("$@"); [ ${#TASKS[@]} -eq 0 ] && TASKS=(:app:compileDebugKotlin)
flock /tmp/daftar-gradle.lock ./gradlew "${TASKS[@]}" --console=plain -q > /tmp/daftar-build-$$.log 2>&1
rc=$?
# FILTER=planner/ tools/compile.sh  -> only show errors from paths containing that text
grep -v -e "JAVA_TOOL_OPTIONS" -e "^w: " /tmp/daftar-build-$$.log | { if [ -n "$FILTER" ]; then grep -e "$FILTER" -e "FAILED"; else cat; fi; } | head -150
rm -f /tmp/daftar-build-$$.log
if [ $rc -eq 0 ]; then echo "BUILD OK"; else echo "BUILD FAILED"; fi
exit $rc
