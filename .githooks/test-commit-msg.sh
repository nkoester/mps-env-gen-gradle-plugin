#!/bin/sh
# Tests .githooks/commit-msg; run: sh .githooks/test-commit-msg.sh
hook="$(dirname "$0")/commit-msg"
tmp="$(mktemp)"
trap 'rm -f "${tmp}"' EXIT
failures=0

# check <expected exit code> <strict 0|1> <message>
check() {
    printf '%s\n' "$3" > "${tmp}"
    COMMIT_MSG_STRICT="$2" sh "${hook}" "${tmp}" 2>/dev/null
    actual=$?
    if [ "${actual}" != "$1" ]; then
        printf 'FAIL (expected %s, got %s, strict=%s): %s\n' "$1" "${actual}" "$2" "$3"
        failures=$((failures + 1))
    fi
}

# valid
check 0 0 "feat: add plugin presets"
check 0 0 "fix(core): open macOS packages for mps.sh"
check 0 0 "feat(gradle-plugin)!: drop per-environment jbrVersion"
check 0 0 "refactor!: rename options"
check 0 0 "docs: add gradle flow diagram

Body text after a blank line.

BREAKING CHANGE: none really"
check 0 0 "chore: 2026.1 support"
check 0 0 "# comment written by git
ci: check commit messages
# another comment"
check 0 0 "fix: subject
# ------------------------ >8 ------------------------
diff below the scissors line
is ignored"
check 0 0 "Merge branch 'feature'"
check 0 1 "Merge pull request #2 from nkoester/feature"
check 0 1 "Revert \"feat: add plugin presets\""
check 0 0 "fixup! feat: add plugin presets"
check 0 0 "squash! feat: add plugin presets"
check 0 0 "amend! feat: add plugin presets"
# exactly 72 characters
check 0 0 "fix: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

# invalid
check 1 0 ""
check 1 0 "# only a comment"
check 1 0 "Open the macOS UI packages when starting MPS via mps.sh"
check 1 0 "feature: add plugin presets"
check 1 0 "Fix: subject"
check 1 0 "fix(plugins): unknown scope"
check 1 0 "fix(): empty scope"
check 1 0 "fix(core: malformed scope"
check 1 0 "fix:no space"
check 1 0 "fix:  two spaces"
check 1 0 "fix: "
check 1 0 "fix: Uppercase description"
check 1 0 "fix: trailing dot."
check 1 0 "fix: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
check 1 0 "fix: subject
body without blank line"
check 1 1 "fixup! feat: add plugin presets"
check 1 1 "squash! feat: add plugin presets"
check 1 1 "amend! feat: add plugin presets"

if [ "${failures}" -gt 0 ]; then
    printf '%s check(s) failed\n' "${failures}"
    exit 1
fi
printf 'all checks passed\n'
