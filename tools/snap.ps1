# Dev helper: screenshot the bottom strip of every monitor, one PNG per screen.
param([string]$Dir = $env:TEMP, [int]$Strip = 360)
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public class Dpi { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }'
[Dpi]::SetProcessDPIAware() | Out-Null
$i = 0
foreach ($s in [System.Windows.Forms.Screen]::AllScreens) {
  $b = $s.Bounds
  $bmp = [System.Drawing.Bitmap]::new([int]$b.Width, [int]$Strip)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen([int]$b.Left, [int]($b.Bottom - $Strip), 0, 0, $bmp.Size)
  $out = Join-Path $Dir "wb-snap-$i.png"
  $bmp.Save($out); $g.Dispose(); $bmp.Dispose()
  "$out  $($s.DeviceName) $b"
  $i++
}
