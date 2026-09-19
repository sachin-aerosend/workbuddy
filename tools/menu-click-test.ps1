# Dev test: open the cat's right-click menu (on monitor 2 or the primary screen) and really click
# its 3rd item ("Play with the ball"). Prints what the app logged.
param([ValidateSet('menu-here', 'menu')][string]$Ui = 'menu-here')
Get-Process electron -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*workbuddy*' } | Stop-Process -Force
Start-Sleep 1
$log = "$env:APPDATA\WorkBuddy\workbuddy.log"; $mark = (Get-Content $log).Count
Set-Location (Join-Path $PSScriptRoot '..')
$env:ELECTRON_RUN_AS_NODE = $null; $env:WB_PROBE = '1'; $env:WB_UI = $Ui; $env:WB_DEMO = $null
Start-Process -FilePath "node_modules\electron\dist\electron.exe" -ArgumentList "."
$hb = $null
for ($i = 0; $i -lt 40 -and -not $hb; $i++) {
  Start-Sleep -Milliseconds 250
  $h = Get-Content $log | Select-Object -Skip $mark | Select-String 'hitboxes .*"bubble":\{' | Select-Object -Last 1
  if ($h) { $hb = ($h.Line -replace '^.*hitboxes ', '') | ConvertFrom-Json }
}
Start-Sleep -Milliseconds 700
Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class GW2 { public delegate bool E(IntPtr h, IntPtr l); [DllImport("user32.dll")] public static extern bool EnumWindows(E cb, IntPtr l); [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint p); [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h); [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r); [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h); [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); public struct R { public int L,T,Rr,B; } }'
[GW2]::SetProcessDPIAware() | Out-Null
$pids = @(Get-Process electron | Where-Object { $_.Path -like '*workbuddy*' } | ForEach-Object Id)
$script:wr = $null; $script:dpi = 96
[GW2]::EnumWindows({ param($h, $l) $p = 0; [GW2]::GetWindowThreadProcessId($h, [ref]$p) | Out-Null
  if ($pids -contains $p -and [GW2]::IsWindowVisible($h)) { $r = New-Object GW2+R; [GW2]::GetWindowRect($h, [ref]$r) | Out-Null
    if ($r.Rr - $r.L -gt 200) { $script:wr = $r; $script:dpi = [GW2]::GetDpiForWindow($h) } }; $true }, [IntPtr]::Zero) | Out-Null
$s = $script:dpi / 96
$x = [int]($script:wr.L + ($hb.bubble.x + $hb.bubble.w / 2) * $s)
$y = [int]($script:wr.T + ($hb.bubble.y + 6 + 6 + 27 * 2 + 13) * $s)
"window=($($script:wr.L),$($script:wr.T)) dpi=$($script:dpi) bubble=$($hb.bubble | ConvertTo-Json -Compress) -> click ($x,$y)"
& (Join-Path $PSScriptRoot 'click.ps1') -X $x -Y $y
Start-Sleep 2
Get-Content $log | Select-Object -Skip $mark | Select-String 'renderer: click|menu close|renderer hover|missed' | ForEach-Object { $_.Line.Substring(25) }
Get-Process electron -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*workbuddy*' } | Stop-Process -Force
