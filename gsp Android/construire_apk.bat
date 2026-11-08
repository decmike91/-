@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
echo ==================================================
echo   GestSP Mobile - construction de l'APK Android
echo ==================================================
rem Android Studio fournit Java (JBR) et le SDK Android.
if not defined JAVA_HOME (
  if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
)
if not defined ANDROID_HOME (
  if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)
if not defined JAVA_HOME (
  echo Java introuvable : installez Android Studio ^(https://developer.android.com/studio^).
  goto :err
)
if not defined ANDROID_HOME (
  echo SDK Android introuvable : ouvrez Android Studio une premiere fois pour l'installer.
  goto :err
)
echo sdk.dir=%ANDROID_HOME:\=\\%> local.properties
call gradlew.bat --no-daemon assembleRelease || goto :err
copy /y app\build\outputs\apk\release\app-release.apk GestSP-Mobile.apk >nul
echo.
echo Termine : %CD%\GestSP-Mobile.apk
echo Copiez ce fichier sur le telephone et ouvrez-le pour l'installer.
pause
exit /b 0
:err
echo.
echo *** ERREUR pendant la construction ***
pause
exit /b 1
