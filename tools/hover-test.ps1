# Dev test: hover the real cursor over the cat's window (wherever it is, any monitor) and report
# whether the window stops being click-through. Restores the cursor afterwards.
param([string]$Proc = 'electron', [int]$Samples = 6)
Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public class HT {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr h, int i);
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
[HT]::SetProcessDPIAware() | Out-Null
$pids = [uint32[]]@(Get-Process $Proc -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*orkBuddy*' -or $_.Path -like '*workbuddy*' } | ForEach-Object Id)
$old = New-Object HT+POINT; [HT]::GetCursorPos([ref]$old) | Out-Null
foreach ($i in 1..$Samples) {
  if (-not [HT]::Find($pids)) { "no window"; break }
  $r = [HT]::R; $scale = [HT]::GetDpiForWindow([HT]::H) / 96
  $cx = [int](($r.L + $r.R) / 2); $cy = [int]($r.B - 30 * $scale)   # just above the cat's feet
  [HT]::SetCursorPos($cx, $cy) | Out-Null; Start-Sleep -Milliseconds 200
  $ex = [HT]::GetWindowLong([HT]::H, -20)
  "hover $i  window=($($r.L),$($r.T)) scale=$scale cursor=($cx,$cy) clickable=$((($ex -band 0x20) -eq 0))"
}
[HT]::SetCursorPos($old.X, $old.Y) | Out-Null
