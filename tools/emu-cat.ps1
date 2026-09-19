# Dev helper: find the cat's overlay windows on the emulator and interact with them.
# usage: emu-cat.ps1 where | tap | long | drag <dx> <dy> | menu <itemIndex>   (menu: long-press, then tap item N, 0-based)
param([string]$Action = 'where', [int]$A = 0, [int]$B = -600)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function Overlays {
  $out = @(); $cur = $null
  foreach ($line in (& $adb shell dumpsys window windows)) {
    # overlay windows are titled with the bare package name (activity windows are "pkg/pkg.Activity")
    if ($line -match 'Window #\d+ Window\{\S+ \S+ (\S+)\}') { $cur = if ($Matches[1] -eq 'com.workbuddy.cat') { 'wb' } else { $null } }
    if ($cur -and $line -match 'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') {
      $out += ,@([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4]); $cur = $null
    }
  }
  $out
}
function Find-CatWindow { Overlays | Where-Object { ($_[2] - $_[0]) -lt 700 -and ($_[3] - $_[1]) -gt 100 -and ([math]::Abs(($_[2]-$_[0]) - ($_[3]-$_[1])) -lt 80) } | Select-Object -First 1 }
$f = Find-CatWindow
if (-not $f) { "cat window not found"; return }
$cx = [int](($f[0] + $f[2]) / 2); $cy = [int]($f[3] - ($f[3] - $f[1]) * 0.25)
switch ($Action) {
  'tap'  { & $adb shell input tap $cx $cy }
  'long' { & $adb shell input swipe $cx $cy $cx $cy 900 }
  'drag' { & $adb shell input swipe $cx $cy ($cx + $A) ($cy + $B) 700 }
  'menu' {
    & $adb shell input swipe $cx $cy $cx $cy 900
    Start-Sleep -Milliseconds 600
    $bub = Overlays | Where-Object { $_ -join ',' -ne ($f -join ',') } | Sort-Object { $_[3] - $_[1] } -Descending | Select-Object -First 1
    if (-not $bub) { "menu not found"; return }
    $items = 7; $h = ($bub[3] - $bub[1]) / $items
    $ty = [int]($bub[1] + $h * ($A + 0.5)); $tx = [int](($bub[0] + $bub[2]) / 2)
    & $adb shell input tap $tx $ty
    "menu [$($bub -join ',')] -> item $A at ($tx,$ty)"
  }
}
"cat window [$($f -join ',')] -> $Action"
