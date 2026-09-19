# Dev helper: build the Android debug APK, install it on the running emulator and switch the accessibility service on.
# usage: emu-install.ps1 [-Fresh] [-NoBuild]      (-Fresh uninstalls first, so settings start from scratch)
param([switch]$Fresh, [switch]$NoBuild)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$svc = 'com.workbuddy.cat/com.workbuddy.cat.WatchService'
if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
  $env:JAVA_HOME = (Get-ChildItem 'C:\Program Files\Microsoft\jdk-*' | Sort-Object Name -Descending | Select-Object -First 1).FullName
}
if (-not $NoBuild) {
  Push-Location "$root\android"
  $out = .\gradlew.bat :app:assembleDebug --console=plain -q 2>&1
  $code = $LASTEXITCODE
  Pop-Location
  if ($code -ne 0) {
    $out | Select-String -Pattern '^e:|error:|FAILED|What went wrong|Unresolved|Caused by' | Select-Object -First 60
    "BUILD FAILED ($code)"; return
  }
  "build ok"
}
$apk = "$root\android\app\build\outputs\apk\debug\app-debug.apk"
if ($Fresh) { & $adb uninstall com.workbuddy.cat | Out-Null }
& $adb install -r $apk | Select-Object -Last 1
& $adb logcat -c
& $adb shell am start -n com.workbuddy.cat/.MainActivity | Out-Null
Start-Sleep 2
& $adb shell pm grant com.workbuddy.cat android.permission.POST_NOTIFICATIONS
& $adb shell settings delete secure enabled_accessibility_services | Out-Null
& $adb shell settings put secure enabled_accessibility_services $svc
& $adb shell settings put secure accessibility_enabled 1
Start-Sleep 2
& $adb shell input keyevent KEYCODE_HOME
Start-Sleep 2
& $adb logcat -d -s WorkBuddy:I AndroidRuntime:E | Select-Object -Last 15
