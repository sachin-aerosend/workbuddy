# Dev test: does "Hide from screen share" really hide the cat from captures? Flips the setting and screenshots.
Get-Process WorkBuddy -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process electron -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*workbuddy*' } | Stop-Process -Force
Start-Sleep 1
$root = Join-Path $PSScriptRoot '..'
$settings = "$env:APPDATA\WorkBuddy\settings.json"
function Set-Share([bool]$hide) {
  node -e "const fs=require('fs');const f=process.argv[1];const s=JSON.parse(fs.readFileSync(f,'utf8'));s.hideFromScreenShare=process.argv[2]==='true';fs.writeFileSync(f,JSON.stringify(s,null,2))" $settings "$hide".ToLower()
}
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public class DpiS { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }'
[DpiS]::SetProcessDPIAware() | Out-Null
function Snap($name) {
  $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds; $w = 600; $h = 360
  $bmp = [System.Drawing.Bitmap]::new($w, $h); $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen($b.Right - $w, $b.Bottom - $h, 0, 0, $bmp.Size)
  $out = Join-Path $env:TEMP "share-$name.png"; $bmp.Save($out); $g.Dispose(); $bmp.Dispose(); $out
}
Set-Share $true
Set-Location $root
$env:ELECTRON_RUN_AS_NODE = $null; $env:WB_CAPTURE = $null; $env:WB_UI = 'clock'
Start-Process -FilePath "node_modules\electron\dist\electron.exe" -ArgumentList "."
Start-Sleep 5;  Snap 'hidden'
Set-Share $false; Start-Sleep 4; Snap 'visible'
Set-Share $true;  Start-Sleep 4; Snap 'hidden-again'
Get-Process electron -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*workbuddy*' } | Stop-Process -Force
