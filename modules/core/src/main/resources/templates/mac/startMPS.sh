#!/bin/sh
#
#
# date: REPLACE_ME__GENERATION_DATE
# version: REPLACE_ME__VERSION
# description: This file automatically generated. Do not modify.
#
#
# usage: [startMPS_MAC_<environment>.sh] [-d]
#
#     -d       -  Enable remote debugging for this start (first free port from the configured one).

# the environment
CONFIG_PATH="REPLACE_ME__CONFIG_PATH"
CONFIG_MPS_PATH="REPLACE_ME__CONFIG_MPS_PATH"
MPS_PATH="REPLACE_ME__MPS_PATH"
MPS_LAUNCHER="REPLACE_ME__MPS_LAUNCHER"
JAVA_HOME_CANDIDATE="REPLACE_ME__JAVA_HOME"
MISSING_MPS_HINT="REPLACE_ME__MISSING_MPS_HINT"
DEBUG_ENABLED="REPLACE_ME__DEBUG_ENABLED"
DEBUG_PORT="REPLACE_ME__DEBUG_PORT"
DEBUG_SUSPEND="REPLACE_ME__DEBUG_SUSPEND"
# fixed node URL port, must be free; empty: the first free one from HTTP_PORT_BASE
HTTP_PORT="REPLACE_ME__HTTP_PORT"
HTTP_PORT_BASE="REPLACE_ME__HTTP_PORT_BASE"

for arg in "$@"; do
    case "${arg}" in -d|--debug) DEBUG_ENABLED="true" ;; esac
done

CURRENT_BASE_PATH="$( cd -- "$(dirname "$0")" >/dev/null 2>&1 && pwd -P )"
cd "${CURRENT_BASE_PATH}" || { printf "Error: Unable to cd into base path\n"; exit 1; }

# rewrites the original location in a file; /bin/sh on macOS is bash, which has ${var//...}
replaceBasePath() {
    while IFS= read -r line || [ -n "${line}" ]; do
        # shellcheck disable=SC3060
        printf '%s\n' "${line//"${CONFIG_PATH}"/"${CURRENT_BASE_PATH}"}"
    done < "$1" > "$1.tmp" && mv "$1.tmp" "$1"
}

# all paths are absolute; after moving the environment they can be fixed in place
testPaths() {
    configured="$( cd -- "${CONFIG_PATH}" >/dev/null 2>&1 && pwd -P )"
    if [ "${configured}" = "${CURRENT_BASE_PATH}" ]; then
        return
    fi
    printf "WARNING: This environment was generated for\n    %s\nbut is located at\n    %s\n" "${CONFIG_PATH}" "${CURRENT_BASE_PATH}"
    answer=""
    if [ -t 0 ]; then
        printf "Fix the paths of this environment? [yN] "
        read -r answer
    fi
    case "${answer}" in
        y|Y)
            for file in "${CURRENT_BASE_PATH}"/startMPS_* "${CURRENT_BASE_PATH}/environment.properties" "${CURRENT_BASE_PATH}/mps/idea.properties"; do
                [ -f "${file}" ] && replaceBasePath "${file}"
            done
            chmod +x "${CURRENT_BASE_PATH}"/startMPS_*.sh
            exec "${CURRENT_BASE_PATH}/$(basename "$0")" "$@"
            ;;
    esac
    printf "Starting with the paths of the original location.\n\n"
}

testMps() {
    if [ ! -f "${MPS_PATH}/${MPS_LAUNCHER}" ]; then
        printf "ERROR: MPS not found at\n    %s\n%s\n" "${MPS_PATH}/${MPS_LAUNCHER}" "${MISSING_MPS_HINT}" >&2
        exit 1
    fi
}

# without nc every port counts as free
isPortInUse() {
    command -v nc >/dev/null 2>&1 && nc -z 127.0.0.1 "$1" >/dev/null 2>&1
}

# first free port from $1 up to $1 + 20
firstFreePort() {
    port="$1"
    while [ "${port}" -le $(( $1 + 20 )) ]; do
        isPortInUse "${port}" || { printf '%s' "${port}"; return 0; }
        port=$(( port + 1 ))
    done
    return 1
}

# mps64.vmoptions belongs to the user; only the node URL port line is kept up to date (several MPS instances
# must not share it). The line is added if it was removed. MPS is not started without a free port.
updateNodeUrlPort() {
    file="${CONFIG_MPS_PATH}/mps64.vmoptions"
    key="-Dide.httpsupport.internalPort="
    found=0
    if [ -n "${HTTP_PORT}" ]; then
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
    while IFS= read -r line || [ -n "${line}" ]; do
        case "${line}" in "${key}"*) line="${key}${port}"; found=1 ;; esac
        printf '%s\n' "${line}"
    done < "${file}" > "${file}.tmp"
    [ "${found}" = 1 ] || printf '%s\n' "${key}${port}" >> "${file}.tmp"
    mv "${file}.tmp" "${file}"
    printf "Node URL port: %s (%s)  http://127.0.0.1:%s/node\n" "${port}" "${origin}" "${port}"
}

# debugging is decided here (configured or -d); only then MPS starts from a copy with the jdwp line on a free port,
# otherwise directly from mps64.vmoptions, so "Edit Custom VM Options" in MPS edits that file
selectVmOptions() {
    file="${CONFIG_MPS_PATH}/mps64.vmoptions"
    copy="${CONFIG_MPS_PATH}/mps64.runtime.vmoptions"
    debugPort=""
    found=0
    VM_OPTIONS_FILE="${file}"
    if [ "${DEBUG_ENABLED}" = "true" ] && ! debugPort="$(firstFreePort "${DEBUG_PORT}")"; then
        printf "WARNING: no free debug port in %s-%s, starting MPS without debugging\n" "${DEBUG_PORT}" "$(( DEBUG_PORT + 20 ))"
        debugPort=""
    fi
    # nothing to change: no debugging requested and none active in the file
    if [ -z "${debugPort}" ] && ! grep -q -- '^-agentlib:jdwp=' "${file}"; then
        return 0
    fi
    jdwp="-agentlib:jdwp=transport=dt_socket,server=y,suspend=${DEBUG_SUSPEND},address=127.0.0.1:${debugPort}"
    while IFS= read -r line || [ -n "${line}" ]; do
        case "${line}" in
            *-agentlib:jdwp=*)
                found=1
                if [ -n "${debugPort}" ]; then line="${jdwp}"; else line="#${line#\#}"; fi
                ;;
        esac
        printf '%s\n' "${line}"
    done < "${file}" > "${copy}"
    if [ "${found}" = 0 ] && [ -n "${debugPort}" ]; then printf '%s\n' "${jdwp}" >> "${copy}"; fi
    if [ -n "${debugPort}" ]; then printf "Debug port:    %s\n" "${debugPort}"; fi
    VM_OPTIONS_FILE="${copy}"
}

testPaths "$@"
testMps
updateNodeUrlPort
selectVmOptions

# variables read by both mps.sh and the native launcher
MPS_PROPERTIES="${CONFIG_MPS_PATH}/idea.properties"
IDEA_VM_OPTIONS="${VM_OPTIONS_FILE}"
MPS_VM_OPTIONS="${IDEA_VM_OPTIONS}"
export MPS_PROPERTIES IDEA_VM_OPTIONS MPS_VM_OPTIONS
if [ -n "${JAVA_HOME_CANDIDATE}" ] && [ -d "${JAVA_HOME_CANDIDATE}" ]; then
    MPS_JDK="${JAVA_HOME_CANDIDATE}"
    export MPS_JDK
fi

# one part per line
printf '\nCalling MPS:\n    MPS_PROPERTIES=%s \\\n    IDEA_VM_OPTIONS=%s \\\n    MPS_VM_OPTIONS=%s \\\n' \
    "${MPS_PROPERTIES}" "${IDEA_VM_OPTIONS}" "${MPS_VM_OPTIONS}"
if [ -n "${MPS_JDK:-}" ]; then printf '    MPS_JDK=%s \\\n' "${MPS_JDK}"; fi
printf '    %s\n\n' "${MPS_PATH}/${MPS_LAUNCHER}"
exec "${MPS_PATH}/${MPS_LAUNCHER}"
