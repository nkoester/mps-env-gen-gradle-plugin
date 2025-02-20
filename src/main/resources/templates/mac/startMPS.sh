#!/bin/sh
#
#
# date: REPLACE_ME__GENERATION_DATE
# version: REPLACE_ME__VERSION
# description: This file automatically generated. Do not modify.
#
#

# go to location of this script
CURRENT_BASE_PATH="$( cd -- "$(dirname "$0")" >/dev/null 2>&1 ; pwd -P )"
cd "${CURRENT_BASE_PATH}" || ( printf "Error: Unable to cd into base path\n" && exit 1 )

# the environment
CONFIG_PATH="REPLACE_ME__CONFIG_PATH"
CONFIG_MPS_PATH="REPLACE_ME__CONFIG_MPS_PATH"
MPS_PATH="REPLACE_ME__MPS_PATH"

CURRENT_IDEA_PATH=$(cat "${CURRENT_BASE_PATH}/idea.properties" | grep idea.config.path | cut -d "=" -f2)

testPaths() {
    printf "Checking path 'tegrity ... "

    # We could double check via this. But that would be overkill. Might be helpful for someone.
    # IDEA_BASE_PATH=$(head -n 1 idea.properties | cut -d "=" -f2 | awk -F'/config' '{print $1}')

    if [ "${CONFIG_PATH}" != "${CURRENT_BASE_PATH}" ] && [ "${CURRENT_BASE_PATH}" != "." ]; then
        printf "fail.\n"
        printf "The base path seems to be broken\n"
        printf '    configured path:         %s\n' "${CONFIG_PATH}"
        printf '    current actual path:     %s\n' "${CURRENT_BASE_PATH}"
        printf '    paths in idea.property   %s\n\n' "${CURRENT_IDEA_PATH}"
        read -p "Automatically fix it? [yN] " ANSWER
        if [ "${ANSWER}" = "y" ] || [ "${ANSWER}" = "Y" ]
        then
            printf "Replacing all wrong paths in 'idea.properties'\n"
            sed -i 's~'"${CONFIG_PATH}"'~'"${CURRENT_BASE_PATH}"'~g' idea.properties

            printf "Replacing all wrong paths in 'environment.env'\n"
            sed -i 's~'"${CONFIG_PATH}"'~'"${CURRENT_BASE_PATH}"'~g' environment.env

            # reload to get the new paths
            CONFIG_PATH=${CURRENT_BASE_PATH}
            CONFIG_MPS_PATH="${CONFIG_MPS_PATH}/mps"

        else
            printf "Will not update paths.\n\n"
            printf 'WARNING: Starting MPS will most likely fail. The configured path %s does not exist anymore. MPS will fall back to the default configuration path.\nPress enter to continue' "${CURRENT_BASE_PATH}"
            read -r _
        fi
    else
        printf "ok.\n"
    fi
}

# run path test
testPaths

printf "Calling MPS via ...\n >>> MPS_PROPERTIES=${CONFIG_MPS_PATH}/idea.properties IDEA_VM_OPTIONS=${CONFIG_MPS_PATH}/mps64.vmoptions  ${MPS_PATH}/bin/mps.sh\n"
MPS_PROPERTIES=${CONFIG_MPS_PATH}/idea.properties IDEA_VM_OPTIONS=${CONFIG_MPS_PATH}/mps64.vmoptions  ${MPS_PATH}/bin/mps.sh
