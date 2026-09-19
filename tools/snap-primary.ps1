# Dev helper: capture the primary monitor N times at the given delays (ms), half-size PNGs.
param([int[]]$Delays = @(0), [string]$Dir = $env:TEMP)
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public class Dpi2 { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }'
[Dpi2]::SetProcessDPIAware() | Out-Null
$b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$n = 0
foreach ($d in $Delays) {
  Start-Sleep -Milliseconds $d
  $bmp = [System.Drawing.Bitmap]::new([int]$b.Width, [int]$b.Height)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen([int]$b.Left, [int]$b.Top, 0, 0, $bmp.Size)
  $half = [System.Drawing.Bitmap]::new($bmp, [int]($b.Width / 2), [int]($b.Height / 2))
  $out = Join-Path $Dir "wb-primary-$n.png"
  $half.Save($out); $g.Dispose(); $bmp.Dispose(); $half.Dispose()
  $out; $n++
}
