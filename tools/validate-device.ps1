param([string]$Adb = 'adb', [string]$Serial = '')
$ErrorActionPreference = 'Stop'
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & ./gradlew.bat -PopenfitValidation=true assembleDebug assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Validation APK build failed' }
    & $Adb @deviceArgs install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'Validation app installation failed' }
    & $Adb @deviceArgs install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Validation tests installation failed' }
    $report = & $Adb @deviceArgs shell am instrument -w com.openfit.validation.debug.test/com.openfit.validation.ValidationInstrumentation
    $report | Write-Output
    if ($LASTEXITCODE -ne 0) { throw 'Instrumentation did not run' }
    if (($report -join "`n") -notmatch '(?m)^\d+ tests; 0 failures\s*$') { throw 'Device fixtures failed; inspect the instrumentation output' }
} finally { Pop-Location }
