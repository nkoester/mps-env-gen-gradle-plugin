#!/usr/bin/env bash
#
# Smoke test (Linux, macOS): generates an environment with the CLI built from this repository, starts MPS through the
# generated start script and checks that MPS comes up. Windows: smoke.ps1.
#
# usage: smoke-test/smoke.sh <mps-version> [<project-dir>]
#   build the CLI first: ./gradlew :cli:installDist
#
# pass: MPS opens $SMOKE_NODE_REF via its node URL (http://127.0.0.1:<port>/node?ref=...): the project is open, the
#       model loaded and an editor shown; without project: the node URL port answers
# fail: "Start Failed" in the output, MPS exits, or $SMOKE_TIMEOUT seconds pass
# MPS answers every node request with HTTP 200; the body tells: "has been opened" (pass), "No project is available"
# or "has not been found" (not ready yet, retried every 2s; each try shows a popup in MPS; 15 times "not found" fail)
#
# environment:
#   SMOKE_TIMEOUT   seconds until MPS must be up (default 300)
#   SMOKE_NODE_REF  URL-encoded node reference to open (default: the root node of smoketest.sandbox in both samples)
#   SMOKE_SHOW=1    Linux: use the current display instead of an Xvfb display
# when MPS is still running at a failure: thread dumps in the start output, Linux also screenshot.png (ImageMagick)
#   SMOKE_WORK      work folder (default: a new temp folder); environment, output and copies of the logs end up there
#   MPS_ENV_GEN     CLI to use (default: modules/cli/build/install/mps-env-gen/bin/mps-env-gen)
#   MPS_ENV_GEN_CACHE  download/distribution cache of the CLI (default: the user cache folder)

set -u

version="${1:?usage: smoke.sh <mps-version> [<project-dir>]}"
project="${2:-}"
repo="$(cd "$(dirname "$0")/.." && pwd -P)"
cli="${MPS_ENV_GEN:-${repo}/modules/cli/build/install/mps-env-gen/bin/mps-env-gen}"
timeout="${SMOKE_TIMEOUT:-300}"
node_ref="${SMOKE_NODE_REF:-r%3Ab8dc92cc-9342-4627-878b-8909a6ba5364%28smoketest.sandbox%29%2F7901695510806614122}"
work="${SMOKE_WORK:-$(mktemp -d "${TMPDIR:-/tmp}/mps-smoke.XXXXXX")}"
name="smoke"
env_dir="${work}/envs/${name}"
output="${work}/start-output.txt"
log="${env_dir}/mps/log/idea.log"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"

case "$(uname -s)" in
    Linux) os="LINUX" ;;
    Darwin) os="MAC" ;;
    *) echo "Unsupported OS $(uname -s); use smoke.ps1 on Windows" >&2; exit 2 ;;
esac

[ -x "${cli}" ] || { echo "CLI not found at ${cli}; run ./gradlew :cli:installDist" >&2; exit 2; }
if [ -n "${project}" ] && [ ! -d "${project}" ]; then
    echo "WARNING: project ${project} not found, starting without project (welcome screen, port check only)"
    project=""
fi
[ -n "${project}" ] || node_ref=""

mkdir -p "${work}"
echo "Work folder: ${work}"

# 1. generate
create=("${cli}" create "${version}" --target "${work}/envs" --name "${name}" --no-copy --no-debug --no-update-checks)
# a copy: MPS writes into the project it opens, the repository stays clean
if [ -n "${project}" ]; then
    rm -rf "${work}/project" && cp -R "${project}" "${work}/project"
    create+=(--project "${work}/project")
fi
echo "Generating: ${create[*]}"
# from the work folder: a folder named like the version in the current folder would be taken as MPS path (BACKLOG)
(cd "${work}" && "${create[@]}") || { echo "FAIL: environment generation failed" >&2; exit 1; }
script="${env_dir}/startMPS_${os}_${name}.sh"
[ -x "${script}" ] || { echo "FAIL: start script ${script} missing" >&2; exit 1; }

# 2. start in its own process group, so the whole tree can be stopped; on Linux on an Xvfb display of our own, so a
#    screenshot can be taken when MPS hangs
xvfb_pid=""
display=""
if [ "${os}" = "LINUX" ] && [ "${SMOKE_SHOW:-0}" != 1 ]; then
    command -v Xvfb >/dev/null || { echo "Xvfb not found (or set SMOKE_SHOW=1)" >&2; exit 2; }
    for n in $(seq 90 130); do [ -e "/tmp/.X11-unix/X${n}" ] || [ -e "/tmp/.X${n}-lock" ] || { display=":${n}"; break; }; done
    [ -n "${display}" ] || { echo "no free X display in :90-:130" >&2; exit 2; }
    Xvfb "${display}" -screen 0 1920x1080x24 -nolisten tcp >"${work}/xvfb.txt" 2>&1 &
    xvfb_pid=$!
    for _ in $(seq 1 50); do [ -e "/tmp/.X11-unix/X${display#:}" ] && break; sleep 0.1; done
fi
set -m
if [ -n "${display}" ]; then
    # without WAYLAND_DISPLAY: on a Wayland desktop the JBR (awt.toolkit.name=auto in MPS 2026.1) would use it and ignore Xvfb
    env -u WAYLAND_DISPLAY DISPLAY="${display}" "${script}" </dev/null >"${output}" 2>&1 &
else
    "${script}" </dev/null >"${output}" 2>&1 &
fi
pid=$!
set +m
start=$(date +%s)

stop() {
    kill -TERM -- "-${pid}" 2>/dev/null
    for _ in 1 2 3 4 5; do kill -0 -- "-${pid}" 2>/dev/null || break; sleep 1; done
    kill -KILL -- "-${pid}" 2>/dev/null
    [ -z "${xvfb_pid}" ] || kill "${xvfb_pid}" 2>/dev/null
}

# what MPS is waiting for: thread dumps into the start output (SIGQUIT), a screenshot of the Xvfb display
diagnose() {
    for java in $(pgrep -g "${pid}" java 2>/dev/null); do kill -QUIT "${java}" 2>/dev/null; done
    if [ -n "${display}" ]; then
        if command -v import >/dev/null; then
            import -display "${display}" -window root "${work}/screenshot.png" 2>/dev/null
        elif command -v xwd >/dev/null && command -v convert >/dev/null; then
            xwd -display "${display}" -root -silent | convert xwd:- "${work}/screenshot.png" 2>/dev/null
        fi
        [ -f "${work}/screenshot.png" ] && echo "Screenshot: ${work}/screenshot.png"
    fi
    sleep 2
}

report() {
    cp "${log}" "${work}/idea.log" 2>/dev/null
    errors="$(grep -c ' ERROR - ' "${log}" 2>/dev/null || true)"
    {
        echo "### MPS ${version} on ${os}: $1"
        echo "- $2"
        echo "- ERROR lines in idea.log: ${errors:-0} (reported only)"
        if [ "${errors:-0}" != 0 ]; then
            echo '```'
            grep ' ERROR - ' "${log}" | cut -c1-300 | head -20
            echo '```'
        fi
    } | tee -a "${summary}"
}

fail() {
    # MPS still running: show what it is doing
    kill -0 "${pid}" 2>/dev/null && diagnose
    stop
    echo "FAIL: $1" >&2
    echo "----- start script output -----"; cat "${output}"
    echo "----- idea.log (last 100 lines) -----"; tail -n 100 "${log}" 2>/dev/null || echo "(no idea.log)"
    report "FAILED" "$1"
    exit 1
}

# 3. watch: fail fast, pass as soon as MPS opens the node
port=""
answer="no answer"
not_found=0
while :; do
    elapsed=$(( $(date +%s) - start ))
    grep -q "Start Failed" "${output}" 2>/dev/null && fail "MPS reported 'Start Failed' after ${elapsed}s"
    kill -0 "${pid}" 2>/dev/null || fail "MPS exited after ${elapsed}s"
    [ -n "${port}" ] || port="$(sed -n 's/^Node URL port: \([0-9]*\).*/\1/p' "${output}" 2>/dev/null | head -n 1)"
    if [ -n "${port}" ]; then
        if [ -z "${node_ref}" ]; then
            curl -s -o /dev/null --max-time 2 "http://127.0.0.1:${port}/" && break
        elif body="$(curl -s --max-time 10 "http://127.0.0.1:${port}/node?ref=${node_ref}")"; then
            case "${body}" in
                *"has been opened"*) break ;;
                *"No project is available"*) answer="no project open yet" ;;
                *"has not been found"*)
                    answer="node not found"
                    # the project is open; models load within seconds, a missing node stays missing
                    not_found=$((not_found + 1))
                    [ "${not_found}" -ge 15 ] && fail "node ${node_ref} not found in the open project (${not_found} tries)"
                    ;;
                *) answer="unexpected answer: $(printf '%s' "${body}" | head -c 200)" ;;
            esac
        fi
    fi
    [ "${elapsed}" -ge "${timeout}" ] && fail "timeout after ${timeout}s (node URL port: ${port:-not printed}, last answer: ${answer})"
    sleep 2
done
elapsed=$(( $(date +%s) - start ))
echo "MPS is up after ${elapsed}s"

stop
report "OK" "$(if [ -n "${node_ref}" ]; then echo "node opened via node URL"; else echo "node URL port answers"; fi) after ${elapsed}s"
exit 0
