# Dev helper: screenshot the running Android emulator to a scaled PNG and print its path (retries flaky captures).
param([string]$Name = 'emu', [double]$Scale = 0.4)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$full = Join-Path $env:TEMP "$Name-full.png"; $out = Join-Path $env:TEMP "$Name.png"
Add-Type -AssemblyName System.Drawing
for ($try = 1; $try -le 5; $try++) {
  & $adb shell screencap -p /sdcard/wb.png 2>$null | Out-Null
  & $adb pull /sdcard/wb.png $full 2>&1 | Out-Null
  try {
    $img = [System.Drawing.Image]::FromFile($full)
    $bmp = [System.Drawing.Bitmap]::new([int]($img.Width * $Scale), [int]($img.Height * $Scale))
    $g = [System.Drawing.Graphics]::FromImage($bmp); $g.InterpolationMode = 'HighQualityBicubic'
    $g.DrawImage($img, 0, 0, $bmp.Width, $bmp.Height); $bmp.Save($out); $g.Dispose(); $bmp.Dispose(); $img.Dispose()
    return $out
  } catch { Start-Sleep -Milliseconds 400 }
}
"screenshot failed"
