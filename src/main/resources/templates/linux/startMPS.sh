#!/usr/bin/env bash
#
#
# date: REPLACE_ME__GENERATION_DATE
# version: REPLACE_ME__VERSION
# description: This file automatically generated. Do not modify.
#
#
# usage: [startLocalizedMPS.sh] [MODE]
#
# Supported modes are:
#              -  If unset or unknown start MPS directly in this terminal.
#     tmuxD    -  Start MPS in a detached tmux session.
#     tmuxA    -  Start MPS in a detached tmux session and attach this terminal directly to it.
#     tmuxLD   -  Opens a new terminal with a 'tail -F' on the idea.log and MPS
#                 itself will be started in a detached tmux session.
#     tmuxLA   -  Opens a new terminal with a 'tail -F' on the idea.log and MPS
#                 itself will be started in a detached tmux session and attach this terminal to it.

# go to location of this script
CURRENT_BASE_PATH="$( cd -- "$(dirname "$0")" >/dev/null 2>&1 ; pwd -P )"
cd "${CURRENT_BASE_PATH}" || ( printf "Error: Unable to cd into base path\n" && exit 1 )

# set the java path if given
JAVA_HOME_CANDIDATE="REPLACE_ME__JAVA_HOME"
if [ -d "${JAVA_HOME_CANDIDATE}" ]; then
    export JAVA_HOME="${JAVA_HOME_CANDIDATE}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
    printf "JAVA_HOME will be overwritten to \n    ${JAVA_HOME}\n\n"
fi

# the environment
export _JAVA_AWT_WM_NONREPARENTING=1
CONFIG_PATH="REPLACE_ME__CONFIG_PATH"
CONFIG_MPS_PATH="REPLACE_ME__CONFIG_MPS_PATH"
MPS_PATH="REPLACE_ME__MPS_PATH"

CONFIG_TMUX_SESSION_NAME="REPLACE_ME__CONFIG_TMUX_SESSION_NAME"
CURRENT_IDEA_PATH=$(cat "${CURRENT_BASE_PATH}/mps/idea.properties" | grep "idea.config.path" | cut -d "=" -f2)

function testPaths {
    printf "Checking path 'tegrity ... "

    # We could double check via this. But that would be overkill. Might be helpful for someone.
    # IDEA_BASE_PATH=$(head -n 1 idea.properties | cut -d "=" -f2 | awk -F'/config' '{print $1}')

    if [[ "${CONFIG_PATH}" != "${CURRENT_BASE_PATH}" ]] && [[ "${CURRENT_BASE_PATH}" != "." ]]; then
        printf "fail.\n"
        printf "The base path seems to be broken\n"
        printf "    configured path:         ${CONFIG_PATH}\n"
        printf "    current actual path:     ${CURRENT_BASE_PATH}\n"
        printf "    paths in idea.property   ${CURRENT_IDEA_PATH}\n\n"
        read -p "Automatically fix it? [yN] " ANSWER
        if [[ "${ANSWER}" == "y" ]] || [[ "${ANSWER}" == "Y" ]]
        then
            printf "Replacing all wrong paths in 'idea.properties'\n"
            sed -i 's~'"${CONFIG_PATH}"'~'"${CURRENT_BASE_PATH}"'~g' idea.properties

            printf "Replacing all wrong paths in 'environment.env'\n"
            sed -i 's~'"${CONFIG_PATH}"'~'"${CURRENT_BASE_PATH}"'~g' environment.env

            # reload to get the new paths
            source environment.env
        else
            printf "Will not update paths.\n\n"
            printf "WARNING: Starting MPS will most likely fail. The configured path ${CURRENT_BASE_PATH} does not exist anymore. MPS will fall back to the default configuration path.\n"
            read -n 1 -s -r -p "Press any key to continue"
        fi
    else
        printf "ok.\n"
    fi
}

function tmuxd {
    printf "Spawning tmux session with name '${CONFIG_TMUX_SESSION_NAME}'\n"
    printf "Calling MPS via ...\n"
    printf " >>> MPS_PROPERTIES=${CONFIG_MPS_PATH}/idea.properties IDEA_VM_OPTIONS=${CONFIG_MPS_PATH}/mps64.vmoptions  ${MPS_PATH}/bin/mps.sh"
    tmux new-session -d -s "$CONFIG_TMUX_SESSION_NAME" "MPS_PROPERTIES=${CONFIG_MPS_PATH}/idea.properties IDEA_VM_OPTIONS=${CONFIG_MPS_PATH}/mps64.vmoptions  ${MPS_PATH}/bin/mps.sh"
}

function tmuxa {
    tmux list-sessions
    tmux attach-session -t $CONFIG_TMUX_SESSION_NAME
}

function followLog {
    touch ${CONFIG_MPS_PATH}/log/idea.log
    $TERMINAL --title="MPS-LOG" -e tail -F ${CONFIG_MPS_PATH}/log/idea.log&
}

# run path test
testPaths

# start in the chosen mode
if [[ "$1" == "tmuxD" ]]; then
    tmuxd
elif [[ "$1" == "tmuxA" ]]; then
    tmuxd
    tmuxa
elif [[ "$1" == "tmuxLD" ]]; then
    followLog
    tmuxd
elif [[ "$1" == "tmuxLA" ]]; then
    followLog
    tmuxd
    tmuxa
else
    printf ' (!)\n'
    printf ' --> No/unknown argument %s given - startig MPS directly in this terminal\n' "${1}"
    printf '     Alternative arguments are: tmuxD, tmuxA, tmuxLD, and tmuxLA\n'
    printf ' (!)\n\n'
    printf "Calling MPS via ... \n >>> MPS_PROPERTIES=${CONFIG_MPS_PATH}/idea.properties IDEA_VM_OPTIONS=${CONFIG_MPS_PATH}/mps64.vmoptions  ${MPS_PATH}/bin/mps.sh\n\n"
    MPS_PROPERTIES=${CONFIG_MPS_PATH}/idea.properties IDEA_VM_OPTIONS=${CONFIG_MPS_PATH}/mps64.vmoptions  ${MPS_PATH}/bin/mps.sh
fi