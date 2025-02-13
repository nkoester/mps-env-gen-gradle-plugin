#!/usr/bin/env bash
#
#
# date: GENERATION_DATE
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
cd "${CURRENT_BASE_PATH}"

# read our environment
# gives us
#    $CONFIG_BASE_PATH
#    $CONFIG_MPS_PATH
#    $CONFIG_TMUX_SESSION_NAME
source environment.env
CURRENT_IDEA_PATH=$(cat ${CURRENT_BASE_PATH}/idea.properties | grep idea.config.path | cut -d "=" -f2)

function testPaths {
    echo -n "Checking path 'tegrity ... "

    # We could double check via this. But thats would be overkill. Might be helpful for someone.
    # IDEA_BASE_PATH=$(head -n 1 idea.properties | cut -d "=" -f2 | awk -F'/config' '{print $1}')

    if [[ "${CONFIG_BASE_PATH}" != "${CURRENT_BASE_PATH}" ]] && [[ "${CURRENT_BASE_PATH}" != "." ]]; then
        echo "fail."
        echo "The base path seems to be broken"
        echo "    configured path:         ${CONFIG_BASE_PATH}"
        echo "    current actual path:     ${CURRENT_BASE_PATH}"
        echo "    paths in idea.property   ${CURRENT_IDEA_PATH}"
        echo ""
        read -p "Automatically fix it? [yN] " ANSWER
        if [[ "${ANSWER}" == "y" ]] || [[ "${ANSWER}" == "Y" ]]
        then
            echo "Replacing all wrong paths in 'idea.properties'"
            sed -i 's~'"${CONFIG_BASE_PATH}"'~'"${CURRENT_BASE_PATH}"'~g' idea.properties

            echo "Replacing all wrong paths in 'environment.env'"
            sed -i 's~'"${CONFIG_BASE_PATH}"'~'"${CURRENT_BASE_PATH}"'~g' environment.env

            # reload to get the new paths
            source environment.env
        else
            echo "Will not update paths."
            echo ""
            echo "WARNING: Starting MPS will most likely fail. The configured path ${CURRENT_BASE_PATH} does not exist anymore. MPS will fall back to the default configuration path."
            read -n 1 -s -r -p "Press any key to continue"
        fi
    else
        echo "ok."
    fi
}

function tmuxd {
    echo "Spawning tmux session with name '${CONFIG_TMUX_SESSION_NAME}'"
    echo "CALLING:     $ MPS_PROPERTIES=${CONFIG_BASE_PATH}/idea.properties IDEA_VM_OPTIONS=${CONFIG_BASE_PATH}/mps64.vmoptions  ${CONFIG_MPS_PATH}/bin/mps.sh"
    tmux new-session -d -s "$CONFIG_TMUX_SESSION_NAME" "MPS_PROPERTIES=${CONFIG_BASE_PATH}/idea.properties IDEA_VM_OPTIONS=${CONFIG_BASE_PATH}/mps64.vmoptions  ${CONFIG_MPS_PATH}/bin/mps.sh"
}

function tmuxa {
    tmux list-sessions
    tmux attach-session -t $CONFIG_TMUX_SESSION_NAME
}

function followLog {
    touch ${CONFIG_BASE_PATH}/log/idea.log
    $TERMINAL --title="MPS-LOG" -e tail -F ${CONFIG_BASE_PATH}/log/idea.log&
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
    echo " (!)"
    echo " --> No/unknown argument ('${1}') given - startig MPS directly in this terminal"
    echo "     Alternative arguments are: tmuxD, tmuxA, tmuxLD, and tmuxLA"
    echo " (!)"
    echo "CALLING:     $ MPS_PROPERTIES=${CONFIG_BASE_PATH}/idea.properties IDEA_VM_OPTIONS=${CONFIG_BASE_PATH}/mps64.vmoptions  ${CONFIG_MPS_PATH}/bin/mps.sh"
    MPS_PROPERTIES=${CONFIG_BASE_PATH}/idea.properties IDEA_VM_OPTIONS=${CONFIG_BASE_PATH}/mps64.vmoptions  ${CONFIG_MPS_PATH}/bin/mps.sh
fi