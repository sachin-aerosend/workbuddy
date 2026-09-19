# Dev helper: real OS-level left click at physical screen coords, then put the cursor back.
param([int]$X, [int]$Y)
Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public class Mouse {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
  public struct POINT { public int X; public int Y; }
}
'@
[Mouse]::SetProcessDPIAware() | Out-Null
$p = New-Object Mouse+POINT; [Mouse]::GetCursorPos([ref]$p) | Out-Null
# approach like a real hand: move near, then onto the button, so hover logic runs
[Mouse]::SetCursorPos($X - 30, $Y - 10) | Out-Null; Start-Sleep -Milliseconds 150
[Mouse]::SetCursorPos($X - 5, $Y) | Out-Null; Start-Sleep -Milliseconds 150
[Mouse]::SetCursorPos($X, $Y) | Out-Null; Start-Sleep -Milliseconds 250
[Mouse]::mouse_event(0x02, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 60
[Mouse]::mouse_event(0x04, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 200
[Mouse]::SetCursorPos($p.X, $p.Y) | Out-Null
"clicked $X,$Y"
