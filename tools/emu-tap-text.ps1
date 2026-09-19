# Dev helper: find something on the emulator screen by its text (or content description) and tap it.
# usage: emu-tap-text.ps1 "Add an app" [-Exact] [-List]      (-List prints every text on screen instead of tapping)
param([string]$Text = '', [switch]$Exact, [switch]$List)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$xml = ''
for ($try = 1; $try -le 4; $try++) {
  & $adb shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
  $xml = (& $adb shell cat /sdcard/ui.xml) -join ''
  if ($xml -match '<hierarchy') { break }
  Start-Sleep -Milliseconds 500
}
if ($xml -notmatch '<hierarchy') { "ui dump failed"; return }
$nodes = [regex]::Matches($xml, '<node [^>]*>') | ForEach-Object {
  $n = $_.Value
  $t = if ($n -match ' text="([^"]*)"') { $Matches[1] } else { '' }
  $d = if ($n -match ' content-desc="([^"]*)"') { $Matches[1] } else { '' }
  if ($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
    [pscustomobject]@{ Label = $(if ($t) { $t } else { $d }); X = [int](([int]$Matches[1] + [int]$Matches[3]) / 2); Y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2) }
  }
} | Where-Object { $_.Label }
if ($List) { $nodes | ForEach-Object { "$($_.X),$($_.Y)  $($_.Label)" }; return }
$hit = if ($Exact) { $nodes | Where-Object { $_.Label -eq $Text } | Select-Object -First 1 } else { $nodes | Where-Object { $_.Label -like "*$Text*" } | Select-Object -First 1 }
if (-not $hit) { "not found: $Text"; return }
& $adb shell input tap $hit.X $hit.Y
"tapped '$($hit.Label)' at ($($hit.X),$($hit.Y))"
