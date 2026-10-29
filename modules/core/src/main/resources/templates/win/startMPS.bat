@ECHO OFF
:
: date: REPLACE_ME__GENERATION_DATE
: version: REPLACE_ME__VERSION
: description: This file automatically generated. Do not modify.
:
: usage: startMPS_WINDOWS_<environment>.bat [-d]
:     -d   Enable remote debugging for this start (first free port from the configured one).
:
SETLOCAL

SET "CONFIG_MPS_PATH=REPLACE_ME__CONFIG_MPS_PATH"
SET "MPS_PATH=REPLACE_ME__MPS_PATH"
SET "MPS_LAUNCHER=REPLACE_ME__MPS_LAUNCHER"
SET "JAVA_HOME_CANDIDATE=REPLACE_ME__JAVA_HOME"
SET "DEBUG_ENABLED=REPLACE_ME__DEBUG_ENABLED"
SET "DEBUG_PORT=REPLACE_ME__DEBUG_PORT"
SET "DEBUG_SUSPEND=REPLACE_ME__DEBUG_SUSPEND"
SET "HTTP_PORT=REPLACE_ME__HTTP_PORT"

FOR %%A IN (%*) DO (
    IF /I "%%~A"=="-d" SET "DEBUG_ENABLED=true"
    IF /I "%%~A"=="--debug" SET "DEBUG_ENABLED=true"
)

:: messages outside of a ( ) block, paths may contain parentheses
IF EXIST "%MPS_PATH%\%MPS_LAUNCHER%" GOTO :mpsFound
ECHO ERROR: MPS not found at
ECHO     %MPS_PATH%\%MPS_LAUNCHER%
ECHO REPLACE_ME__MISSING_MPS_HINT
PAUSE
EXIT /B 1
:mpsFound

:: mps64.vmoptions belongs to the user; only the node URL port line is kept up to date (several MPS instances
:: must not share it). The line is added if it was removed.
SET "VM_OPTIONS_FILE=%CONFIG_MPS_PATH%\mps64.vmoptions"
CALL :firstFreePort %HTTP_PORT% NODE_PORT
IF NOT DEFINED NODE_PORT SET "NODE_PORT=%HTTP_PORT%"
findstr /V /C:"-Dide.httpsupport.internalPort=" "%VM_OPTIONS_FILE%" > "%VM_OPTIONS_FILE%.tmp"
>>"%VM_OPTIONS_FILE%.tmp" ECHO -Dide.httpsupport.internalPort=%NODE_PORT%
MOVE /Y "%VM_OPTIONS_FILE%.tmp" "%VM_OPTIONS_FILE%" >NUL
ECHO Node URL port: %NODE_PORT% (http://127.0.0.1:%NODE_PORT%/node)

:: debugging is decided here (configured or -d); only then MPS starts from a copy with the jdwp line on a free port,
:: otherwise directly from mps64.vmoptions, so "Edit Custom VM Options" in MPS edits that file
SET "DEBUG_PORT_FREE="
IF /I "%DEBUG_ENABLED%"=="true" CALL :firstFreePort %DEBUG_PORT% DEBUG_PORT_FREE
IF /I "%DEBUG_ENABLED%"=="true" IF NOT DEFINED DEBUG_PORT_FREE ECHO WARNING: no free debug port from %DEBUG_PORT%, starting MPS without debugging
SET "JDWP_ACTIVE="
findstr /B /C:"-agentlib:jdwp=" "%VM_OPTIONS_FILE%" >NUL && SET "JDWP_ACTIVE=1"
IF NOT DEFINED DEBUG_PORT_FREE IF NOT DEFINED JDWP_ACTIVE GOTO :vmOptionsSelected
SET "VM_OPTIONS_FILE=%CONFIG_MPS_PATH%\mps64.runtime.vmoptions"
findstr /V /C:"-agentlib:jdwp" "%CONFIG_MPS_PATH%\mps64.vmoptions" > "%VM_OPTIONS_FILE%"
IF DEFINED DEBUG_PORT_FREE >>"%VM_OPTIONS_FILE%" ECHO -agentlib:jdwp=transport=dt_socket,server=y,suspend=%DEBUG_SUSPEND%,address=127.0.0.1:%DEBUG_PORT_FREE%
IF DEFINED DEBUG_PORT_FREE ECHO Debug port:    %DEBUG_PORT_FREE%
:vmOptionsSelected

:: variables read by both mps.bat and the native launcher
SET "MPS_PROPERTIES=%CONFIG_MPS_PATH%\idea.properties"
SET "IDEA_VM_OPTIONS=%VM_OPTIONS_FILE%"
SET "MPS_VM_OPTIONS=%VM_OPTIONS_FILE%"
IF NOT "%JAVA_HOME_CANDIDATE%"=="" IF EXIST "%JAVA_HOME_CANDIDATE%\bin\java.exe" SET "MPS_JDK=%JAVA_HOME_CANDIDATE%"

::----------------------------------------------------------------------
ECHO Running MPS with the following settings:
ECHO MPS_PROPERTIES=%MPS_PROPERTIES%
ECHO IDEA_VM_OPTIONS=%IDEA_VM_OPTIONS%
ECHO MPS_JDK=%MPS_JDK%
::----------------------------------------------------------------------

start "REPLACE_ME__ENVIRONMENT_NAME" "%MPS_PATH%\%MPS_LAUNCHER%"
EXIT /B 0

:: sets variable %2 to the first port from %1 up to %1 + 20 nobody listens on
:firstFreePort
SET "%2="
SET /A FIRST_FREE_LAST=%1+20
FOR /L %%P IN (%1,1,%FIRST_FREE_LAST%) DO (
    IF NOT DEFINED %2 (
        netstat -an -p TCP | findstr /C:":%%P " | findstr LISTENING >NUL || SET "%2=%%P"
    )
)
EXIT /B 0
