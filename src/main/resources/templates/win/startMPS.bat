@ECHO OFF
:
: date: GENERATION_DATE
:

::----------------------------------------------------------------------
echo Running MPS with the following settings:
echo MPS_PROPERTIES=CONFIG_BASE_PATH\idea.properties
echo IDEA_VM_OPTIONS=CONFIG_BASE_PATH\mps64.vmoptions
::----------------------------------------------------------------------

SET MPS_PROPERTIES=CONFIG_BASE_PATH\idea.properties
SET IDEA_VM_OPTIONS=CONFIG_BASE_PATH\mps64.vmoptions

start CONFIG_MPS_PATH/bin/win/mps.bat
