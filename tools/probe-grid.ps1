# Dev: sweep the cursor over a few points of the cat's window so the app logs renderer vs main coordinates.
param([string]$Proc = 'electron')
Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public class PG {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
  public struct POINT { public int X; public int Y; }
  public struct RECT { public int L, T, R, B; }
  public static IntPtr H; public static RECT R;
  public static bool Find(uint[] pids) { H = IntPtr.Zero; EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p);
      if (Array.IndexOf(pids, p) >= 0 && IsWindowVisible(h)) { RECT r; GetWindowRect(h, out r); if (r.R - r.L > 200) { H = h; R = r; } } return true; }, IntPtr.Zero); return H != IntPtr.Zero; }
}
'@
[PG]::SetProcessDPIAware() | Out-Null
$pids = [uint32[]]@(Get-Process $Proc -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*orkBuddy*' -or $_.Path -like '*workbuddy*' } | ForEach-Object Id)
$old = New-Object PG+POINT; [PG]::GetCursorPos([ref]$old) | Out-Null
if (-not [PG]::Find($pids)) { "no window"; return }
$r = [PG]::R
"window physical rect=($($r.L),$($r.T))-($($r.R),$($r.B)) size=$($r.R-$r.L)x$($r.B-$r.T) dpi=$([PG]::GetDpiForWindow([PG]::H))"
foreach ($f in @(@(0.25,0.3), @(0.5,0.5), @(0.75,0.9))) {
  $x = [int]($r.L + ($r.R - $r.L) * $f[0]); $y = [int]($r.T + ($r.B - $r.T) * $f[1])
  [PG]::SetCursorPos($x, $y - 3) | Out-Null; Start-Sleep -Milliseconds 120
  [PG]::SetCursorPos($x, $y) | Out-Null; Start-Sleep -Milliseconds 1100
  "cursor physical=($x,$y) window-local physical=($($x - $r.L),$($y - $r.T))"
}
[PG]::SetCursorPos($old.X, $old.Y) | Out-Null
