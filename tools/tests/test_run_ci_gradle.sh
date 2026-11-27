#!/usr/bin/env bash
set -euo pipefail

tools_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
cd "$fixture"
mkdir bin

cat > bin/sleep <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> delays
EOF
chmod +x bin/sleep

cat > gradlew <<'EOF'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> calls
if [[ $TABULA_RETRY_CASE == recover && $(wc -l < calls) -gt 1 ]]; then
    exit 0
fi

if [[ $TABULA_RETRY_CASE == multiple ]]; then
    echo 'FAILURE: Build completed with 2 failures.'
fi
echo '* What went wrong:'
case "$TABULA_RETRY_CASE" in
    assertion)
        echo "Execution failed for task ':app:testDebugUnitTest'."
        echo '> There were failing tests.'
        echo '* Try:'
        ;;
    compile)
        echo "Execution failed for task ':app:compileDebugKotlin'."
        echo '> Compilation error. See log for more details.'
        echo '* Try:'
        ;;
esac
if [[ $TABULA_RETRY_CASE == task_classpath ]]; then
    echo "Execution failed for task ':app:compileDebugKotlin'."
    echo "> Could not resolve all files for configuration ':app:debugCompileClasspath'."
else
    echo "Could not resolve all artifacts for configuration 'classpath'."
fi
echo "> Could not GET 'https://repo.maven.apache.org/maven2/test.pom'. Received status code 429 from server: Too Many Requests"
echo '* Try:'
exit 42
EOF

run_case() {
    local case_name=$1 expected_status=$2 expected_calls=$3 expected_delays=$4
    local status=0
    : > calls
    : > delays
    TABULA_RETRY_CASE="$case_name" PATH="$fixture/bin:$PATH" \
        bash "$tools_dir/run-ci-gradle.sh" --no-daemon :app:testDebugUnitTest > run.log 2>&1 || status=$?

    if [[ $status -ne $expected_status || $(wc -l < calls) -ne $expected_calls ||
        $(paste -sd ' ' delays) != "$expected_delays" ]] ||
        grep -Fvxq -- '--console=plain --no-daemon :app:testDebugUnitTest' calls; then
        printf 'FAIL %s: exit=%s, calls=%s, delays=%s\n' "$case_name" "$status" "$(wc -l < calls)" "$(cat delays)"
        cat run.log
        exit 1
    fi
    echo "PASS $case_name"
}

run_case recover 0 2 '20'
run_case exhausted 42 3 '20 60'
run_case task_classpath 42 3 '20 60'
run_case assertion 42 1 ''
run_case compile 42 1 ''
run_case multiple 42 1 ''
