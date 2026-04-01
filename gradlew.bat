@echo off
setlocal

set "APP_HOME=%~dp0"
set "JAVA_EXE="

if not "%JAVA_HOME%"=="" (
  if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

if "%JAVA_EXE%"=="" (
  for /f "delims=" %%i in ('where java 2^>nul') do (
    set "JAVA_EXE=%%i"
    goto :run
  )
)

if "%JAVA_EXE%"=="" if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_EXE=C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
if "%JAVA_EXE%"=="" if exist "C:\Program Files (x86)\Android\Android Studio\jbr\bin\java.exe" set "JAVA_EXE=C:\Program Files (x86)\Android\Android Studio\jbr\bin\java.exe"
if "%JAVA_EXE%"=="" if exist "C:\Program Files\Java\jdk-17.0.12\bin\java.exe" set "JAVA_EXE=C:\Program Files\Java\jdk-17.0.12\bin\java.exe"
if "%JAVA_EXE%"=="" if exist "C:\Program Files\Java\jdk-17\bin\java.exe" set "JAVA_EXE=C:\Program Files\Java\jdk-17\bin\java.exe"
if "%JAVA_EXE%"=="" if exist "%ProgramFiles%\Eclipse Adoptium\jdk-17\bin\java.exe" set "JAVA_EXE=%ProgramFiles%\Eclipse Adoptium\jdk-17\bin\java.exe"
if "%JAVA_EXE%"=="" if exist "%ProgramFiles%\Microsoft\jdk-17\bin\java.exe" set "JAVA_EXE=%ProgramFiles%\Microsoft\jdk-17\bin\java.exe"

:run
if "%JAVA_EXE%"=="" (
  echo Java not found. Install JDK 17 and set JAVA_HOME, or add java.exe to PATH.
  exit /b 1
)

"%JAVA_EXE%" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*

endlocal
