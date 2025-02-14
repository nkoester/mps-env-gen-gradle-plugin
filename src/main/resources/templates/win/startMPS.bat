@ECHO OFF
:
: date: REPLACE_ME__GENERATION_DATE
:

::----------------------------------------------------------------------
echo Running MPS with the following settings:
echo MPS_PROPERTIES=REPLACE_ME__CONFIG_MPS_PATH\idea.properties
echo IDEA_VM_OPTIONS=REPLACE_ME__CONFIG_MPS_PATH\mps64.vmoptions
::----------------------------------------------------------------------

SET MPS_PROPERTIES=REPLACE_ME__CONFIG_MPS_PATH\idea.properties
SET IDEA_VM_OPTIONS=REPLACE_ME__CONFIG_MPS_PATH\mps64.vmoptions


start "REPLACE_ME__EVIRONMENT_NAME" REPLACE_ME__MPS_PATH\bin\mps.bat
