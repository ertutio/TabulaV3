#!/usr/bin/env bash
set -euo pipefail

log=$(mktemp)
trap 'rm -f "$log"' EXIT
delays=(20 60)

for attempt in 0 1 2; do
    if bash ./gradlew --console=plain "$@" 2>&1 | tee "$log"; then
        exit 0
    else
        statuses=("${PIPESTATUS[@]}")
    fi
    if (( statuses[1] != 0 )); then
        exit "${statuses[1]}"
    fi

    summary=$(sed -n '/^\* What went wrong:$/,/^\* Try:$/p' "$log")
    if (( attempt == ${#delays[@]} )) ||
        grep -Eq '^FAILURE: Build completed with [0-9]+ failures\.' "$log" ||
        ! grep -Eq 'Could not resolve all (artifacts|files) for configuration' <<< "$summary" ||
        ! grep -Eq "Could not (GET|HEAD) 'https://(repo\.maven\.apache\.org|repo1\.maven\.org|plugins\.gradle\.org)/[^']+'.*Received status code 429 from server" <<< "$summary"; then
        exit "${statuses[0]}"
    fi

    delay=${delays[attempt]}
    echo "::warning title=Gradle dependency resolution::HTTP 429 while resolving dependencies. Retrying in ${delay}s."
    sleep "$delay"
done
