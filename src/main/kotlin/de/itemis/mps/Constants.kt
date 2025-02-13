package de.itemis.mps

object Constants {
    // generic

    internal const val README_FILENAME = "README.md"
    internal const val README_TEMPLATE_PATH = "/templates/README.md"
    internal const val IDEA_PROPERTIES_FILENAME = "idea.properties"
    internal const val IDEA_PROPERTIES_TEMPLATE_PATH = "/templates/mps/idea.properties"
    internal const val MPS_VMOPTIONS_FILENAME = "mps64.vmoptions"
    internal const val MPS_VMOPTIONS_TEMPLATE_PATH = "/templates/mps/mps64.vmoptions"

    internal const val MPS_CONFIG_TEMPLATE_PATH = "/templates/mps/config/options"

    // linux

    internal const val ENVIRONMENT_LINUX_FILENAME = "environment.env"
    internal const val ENVIRONMENT_LINUX_TEMPLATE_PATH = "/templates/linux/environment.env"

    internal const val MPS_RUN_SCRIPT_LINUX_FILENAME = "startMPS-{0}.sh"
    internal const val MPS_RUN_SCRIPT_LINUX_TEMPLATE_PATH = "/templates/linux/startMPS.sh"

    // mac TODO
    internal const val MPS_RUN_SCRIPT_MAC_FILENAME = "startMPS{0}.sh"
    internal const val MPS_RUN_SCRIPT_MAC_TEMPLATE_PATH = "/templates/mac/startMPS.bat"

    // win TODO
    internal const val MPS_RUN_SCRIPT_WIN_FILENAME = "startMPS-{0}.bat"
    internal const val MPS_RUN_SCRIPT_WIN_TEMPLATE_PATH = "/templates/win/startMPS.bat"
}