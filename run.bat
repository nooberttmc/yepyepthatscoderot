@echo off
rem Compiles and runs VOIDBEAT. Requires a JDK 17 or newer.
cd /d "%~dp0"
if not exist out mkdir out
javac -nowarn -d out src\voidbeat\*.java || (pause & exit /b 1)
java -Xmx1g -cp out voidbeat.Main %*
