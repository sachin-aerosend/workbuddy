# Dev helper: launch the dev app with a UI state open and screenshot the bottom-right of the primary screen.
param([string]$Ui = 'menu', [int]$Wait = 4)
Get-Process electron -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*workbuddy*' } | Stop-Process -Force
Start-Sleep -Milliseconds 500
Set-Location (Join-Path $PSScriptRoot '..')
Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue
$env:WB_CAPTURE = '1'; $env:WB_UI = $Ui
Start-Process -FilePath "node_modules\electron\dist\electron.exe" -ArgumentList "."
Start-Sleep $Wait
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public class DpiU { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }'
[DpiU]::SetProcessDPIAware() | Out-Null
$b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$w = 700; $h = 560
$bmp = [System.Drawing.Bitmap]::new($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($b.Right - $w, $b.Bottom - $h, 0, 0, $bmp.Size)
$out = Join-Path $env:TEMP "wb-ui-$Ui.png"
$bmp.Save($out); $g.Dispose(); $bmp.Dispose()
$out
