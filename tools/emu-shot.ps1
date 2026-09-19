# Dev helper: screenshot the running Android emulator to a scaled PNG and print its path (retries flaky captures).
# usage: emu-shot.ps1 [-Name emu] [-Scale 0.4] [-CropTop 0] [-CropH 0]     (CropH > 0 keeps only rows CropTop..CropTop+CropH of the full-size shot)
param([string]$Name = 'emu', [double]$Scale = 0.4, [int]$CropTop = 0, [int]$CropH = 0)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$full = Join-Path $env:TEMP "$Name-full.png"; $out = Join-Path $env:TEMP "$Name.png"
Add-Type -AssemblyName System.Drawing
for ($try = 1; $try -le 5; $try++) {
  & $adb shell screencap -p /sdcard/wb.png 2>$null | Out-Null
  & $adb pull /sdcard/wb.png $full 2>&1 | Out-Null
  try {
    $img = [System.Drawing.Image]::FromFile($full)
    $srcH = if ($CropH -gt 0) { [math]::Min($CropH, $img.Height - $CropTop) } else { $img.Height }
    $srcTop = if ($CropH -gt 0) { $CropTop } else { 0 }
    $bmp = [System.Drawing.Bitmap]::new([int]($img.Width * $Scale), [int]($srcH * $Scale))
    $g = [System.Drawing.Graphics]::FromImage($bmp); $g.InterpolationMode = 'NearestNeighbor'; $g.PixelOffsetMode = 'Half'
    $g.DrawImage($img, [System.Drawing.Rectangle]::new(0, 0, $bmp.Width, $bmp.Height), [System.Drawing.Rectangle]::new(0, $srcTop, $img.Width, $srcH), 'Pixel')
    $bmp.Save($out); $g.Dispose(); $bmp.Dispose(); $img.Dispose()
    return $out
  } catch { Start-Sleep -Milliseconds 400 }
}
"screenshot failed"
