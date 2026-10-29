#!/usr/bin/env bash
#
#
# date: REPLACE_ME__GENERATION_DATE
# version: REPLACE_ME__VERSION
# description: This file automatically generated. Do not modify.
#
#
# usage: [startMPS_LINUX_<environment>.sh] [-d] [MODE]
#
#     -d       -  Enable remote debugging for this start (first free port from the configured one).
# Supported modes are:
#              -  If unset or unknown start MPS directly in this terminal.
#     tmuxD    -  Start MPS in a detached tmux session.
#     tmuxA    -  Start MPS in a detached tmux session and attach this terminal directly to it.
#     tmuxLD   -  Opens a new terminal with a 'tail -F' on the idea.log and MPS
#                 itself will be started in a detached tmux session.
#     tmuxLA   -  Opens a new terminal with a 'tail -F' on the idea.log and MPS
#                 itself will be started in a detached tmux session and attach this terminal to it.

# the environment
CONFIG_PATH="REPLACE_ME__CONFIG_PATH"
CONFIG_MPS_PATH="REPLACE_ME__CONFIG_MPS_PATH"
MPS_PATH="REPLACE_ME__MPS_PATH"
MPS_LAUNCHER="REPLACE_ME__MPS_LAUNCHER"
JAVA_HOME_CANDIDATE="REPLACE_ME__JAVA_HOME"
CONFIG_TMUX_SESSION_NAME="REPLACE_ME__ENVIRONMENT_NAME"
MISSING_MPS_HINT="REPLACE_ME__MISSING_MPS_HINT"
DEBUG_ENABLED="REPLACE_ME__DEBUG_ENABLED"
DEBUG_PORT="REPLACE_ME__DEBUG_PORT"
DEBUG_SUSPEND="REPLACE_ME__DEBUG_SUSPEND"
# fixed node URL port, must be free; empty: the first free one from HTTP_PORT_BASE
HTTP_PORT="REPLACE_ME__HTTP_PORT"
HTTP_PORT_BASE="REPLACE_ME__HTTP_PORT_BASE"

MODE=""
for arg in "$@"; do
    case "${arg}" in
        -d|--debug) DEBUG_ENABLED="true" ;;
        *) MODE="${arg}" ;;
    esac
done

CURRENT_BASE_PATH="$( cd -- "$(dirname "$0")" >/dev/null 2>&1 && pwd -P )"
cd "${CURRENT_BASE_PATH}" || { printf "Error: Unable to cd into base path\n"; exit 1; }

# rewrites the original location in a file; bash substitution, so no path character acts as delimiter
function replaceBasePath {
    local file="$1" line
    while IFS= read -r line || [[ -n "${line}" ]]; do
        printf '%s\n' "${line//"${CONFIG_PATH}"/"${CURRENT_BASE_PATH}"}"
    done < "${file}" > "${file}.tmp" && mv "${file}.tmp" "${file}"
}

# all paths are absolute; after moving the environment they can be fixed in place
function testPaths {
    local configured answer=""
    configured="$( cd -- "${CONFIG_PATH}" >/dev/null 2>&1 && pwd -P )"
    if [[ "${configured}" == "${CURRENT_BASE_PATH}" ]]; then
        return
    fi
    printf "WARNING: This environment was generated for\n    %s\nbut is located at\n    %s\n" "${CONFIG_PATH}" "${CURRENT_BASE_PATH}"
    [[ -t 0 ]] && read -r -p "Fix the paths of this environment? [yN] " answer
    if [[ "${answer}" == [yY] ]]; then
        local file
        for file in "${CURRENT_BASE_PATH}"/startMPS_* "${CURRENT_BASE_PATH}/environment.properties" "${CURRENT_BASE_PATH}/mps/idea.properties"; do
            [[ -f "${file}" ]] && replaceBasePath "${file}"
        done
        chmod +x "${CURRENT_BASE_PATH}"/startMPS_*.sh
        exec "${CURRENT_BASE_PATH}/$(basename "$0")" "$@"
    fi
    printf "Starting with the paths of the original location.\n\n"
}

function testMps {
    if [[ ! -f "${MPS_PATH}/${MPS_LAUNCHER}" ]]; then
        printf "ERROR: MPS not found at\n    %s\n%s\n" "${MPS_PATH}/${MPS_LAUNCHER}" "${MISSING_MPS_HINT}" >&2
        exit 1
    fi
}

function isPortInUse {
    if command -v ss >/dev/null 2>&1; then
        [[ -n "$(ss -Hltn "sport = :$1" 2>/dev/null)" ]]
    else
        (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
    fi
}

# first free port from $1 up to $1 + 20
function firstFreePort {
    local port
    for (( port = $1; port <= $1 + 20; port++ )); do
        isPortInUse "${port}" || { printf '%s' "${port}"; return 0; }
    done
    return 1
}

# mps64.vmoptions belongs to the user; only the node URL port line is kept up to date (several MPS instances
# must not share it). The line is added if it was removed. MPS is not started without a free port.
function updateNodeUrlPort {
    local file="${CONFIG_MPS_PATH}/mps64.vmoptions" key="-Dide.httpsupport.internalPort=" port origin line found=0
    if [[ -n "${HTTP_PORT}" ]]; then
        if isPortInUse "${HTTP_PORT}"; then
            printf "ERROR: node URL port %s (fixed for this environment) is in use; MPS is not started.\n" "${HTTP_PORT}" >&2
            printf "Stop what uses it or regenerate the environment with another or no fixed port.\n" >&2
            exit 1
        fi
        port="${HTTP_PORT}"
        origin="fixed"
    elif port="$(firstFreePort "${HTTP_PORT_BASE}")"; then
        origin="first free from ${HTTP_PORT_BASE}"
    else
        printf "ERROR: no free node URL port in %s-%s; MPS is not started.\n" "${HTTP_PORT_BASE}" "$(( HTTP_PORT_BASE + 20 ))" >&2
        exit 1
    fi
    while IFS= read -r line || [[ -n "${line}" ]]; do
        if [[ "${line}" == "${key}"* ]]; then line="${key}${port}"; found=1; fi
        printf '%s\n' "${line}"
    done < "${file}" > "${file}.tmp"
    (( found )) || printf '%s\n' "${key}${port}" >> "${file}.tmp"
    mv "${file}.tmp" "${file}"
    printf "Node URL port: %s (%s)  http://127.0.0.1:%s/node\n" "${port}" "${origin}" "${port}"
}

# debugging is decided here (configured or -d); only then MPS starts from a copy with the jdwp line on a free port,
# otherwise directly from mps64.vmoptions, so "Edit Custom VM Options" in MPS edits that file
function selectVmOptions {
    local file="${CONFIG_MPS_PATH}/mps64.vmoptions" copy="${CONFIG_MPS_PATH}/mps64.runtime.vmoptions" debugPort="" line found=0
    VM_OPTIONS_FILE="${file}"
    if [[ "${DEBUG_ENABLED}" == "true" ]]; then
        debugPort="$(firstFreePort "${DEBUG_PORT}")" \
            || printf "WARNING: no free debug port in %s-%s, starting MPS without debugging\n" "${DEBUG_PORT}" "$(( DEBUG_PORT + 20 ))"
    fi
    # nothing to change: no debugging requested and none active in the file
    if [[ -z "${debugPort}" ]] && ! grep -q -- '^-agentlib:jdwp=' "${file}"; then
        return 0
    fi
    while IFS= read -r line || [[ -n "${line}" ]]; do
        if [[ "${line}" == *"-agentlib:jdwp="* ]]; then
            found=1
            if [[ -n "${debugPort}" ]]; then
                line="-agentlib:jdwp=transport=dt_socket,server=y,suspend=${DEBUG_SUSPEND},address=127.0.0.1:${debugPort}"
            else
                line="#${line#\#}"
            fi
        fi
        printf '%s\n' "${line}"
    done < "${file}" > "${copy}"
    if (( ! found )) && [[ -n "${debugPort}" ]]; then
        printf '%s\n' "-agentlib:jdwp=transport=dt_socket,server=y,suspend=${DEBUG_SUSPEND},address=127.0.0.1:${debugPort}" >> "${copy}"
    fi
    [[ -n "${debugPort}" ]] && printf "Debug port:    %s\n" "${debugPort}"
    VM_OPTIONS_FILE="${copy}"
    return 0
}

testPaths "$@"
testMps
updateNodeUrlPort
selectVmOptions

# variables read by both mps.sh and the native launcher
export MPS_PROPERTIES="${CONFIG_MPS_PATH}/idea.properties"
export IDEA_VM_OPTIONS="${VM_OPTIONS_FILE}"
export MPS_VM_OPTIONS="${IDEA_VM_OPTIONS}"
export _JAVA_AWT_WM_NONREPARENTING=1
if [[ -n "${JAVA_HOME_CANDIDATE}" && -d "${JAVA_HOME_CANDIDATE}" ]]; then
    export MPS_JDK="${JAVA_HOME_CANDIDATE}"
fi

# a tmux server does not inherit this environment, so it is passed on the command line
MPS_COMMAND=("MPS_PROPERTIES=${MPS_PROPERTIES}" "IDEA_VM_OPTIONS=${IDEA_VM_OPTIONS}" "MPS_VM_OPTIONS=${MPS_VM_OPTIONS}"
    "_JAVA_AWT_WM_NONREPARENTING=1")
[[ -n "${MPS_JDK:-}" ]] && MPS_COMMAND+=("MPS_JDK=${MPS_JDK}")
MPS_COMMAND+=("${MPS_PATH}/${MPS_LAUNCHER}")

# one line, as tmux runs it
function mpsCommand {
    local part line=""
    for part in "${MPS_COMMAND[@]}"; do line+="${line:+ }$(printf '%q' "${part}")"; done
    printf '%s' "${line}"
}

# one part per line; still a valid command to copy
function printMpsCommand {
    local i last=$(( ${#MPS_COMMAND[@]} - 1 ))
    printf "\nCalling MPS:\n"
    for (( i = 0; i <= last; i++ )); do
        printf '    %q' "${MPS_COMMAND[i]}"
        if (( i < last )); then printf ' \\'; fi
        printf '\n'
    done
    printf '\n'
}

function tmuxd {
    printMpsCommand
    printf "Spawning tmux session '%s'\n" "${CONFIG_TMUX_SESSION_NAME}"
    tmux new-session -d -s "${CONFIG_TMUX_SESSION_NAME}" "$(mpsCommand)"
}

function tmuxa {
    tmux list-sessions
    tmux attach-session -t "${CONFIG_TMUX_SESSION_NAME}"
}

function followLog {
    mkdir -p "${CONFIG_MPS_PATH}/log"
    touch "${CONFIG_MPS_PATH}/log/idea.log"
    if [[ -z "${TERMINAL:-}" ]]; then
        printf "WARNING: \$TERMINAL is not set, not opening a log terminal\n"
        return
    fi
    "${TERMINAL}" --title="MPS-LOG" -e tail -F "${CONFIG_MPS_PATH}/log/idea.log" &
}

# start in the chosen mode
if [[ "${MODE}" == "tmuxD" ]]; then
    tmuxd
elif [[ "${MODE}" == "tmuxA" ]]; then
    tmuxd
    tmuxa
elif [[ "${MODE}" == "tmuxLD" ]]; then
    followLog
    tmuxd
elif [[ "${MODE}" == "tmuxLA" ]]; then
    followLog
    tmuxd
    tmuxa
else
    if [[ -n "${MODE}" ]]; then
        printf ' --> Unknown argument %s - starting MPS directly in this terminal\n' "${MODE}"
        printf '     Alternative arguments are: tmuxD, tmuxA, tmuxLD, and tmuxLA\n\n'
    fi
    printMpsCommand
    exec "${MPS_PATH}/${MPS_LAUNCHER}"
fi
