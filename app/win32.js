// Detects whether the foreground window is full-screen (videos, games, presentations),
// so the cat can step aside. Falls back to "never full-screen" if the FFI can't load.
let check = () => null;
let foreground = () => null;
let leftDown = () => null;  // null = unknown (renderer's pointerup is used instead)

try {
  const koffi = require('koffi');
  const user32 = koffi.load('user32.dll');
  const RECT = koffi.struct('RECT', { left: 'long', top: 'long', right: 'long', bottom: 'long' });
  const MONITORINFO = koffi.struct('MONITORINFO', { cbSize: 'uint32', rcMonitor: RECT, rcWork: RECT, dwFlags: 'uint32' });
  const GetForegroundWindow = user32.func('void * __stdcall GetForegroundWindow()');
  const GetWindowRect = user32.func('bool __stdcall GetWindowRect(void *hWnd, _Out_ RECT *rect)');
  const MonitorFromWindow = user32.func('void * __stdcall MonitorFromWindow(void *hWnd, uint32 flags)');
  const GetMonitorInfoW = user32.func('bool __stdcall GetMonitorInfoW(void *hMon, _Inout_ MONITORINFO *info)');
  const GetClassNameW = user32.func('int __stdcall GetClassNameW(void *hWnd, _Out_ uint16 *buf, int max)');

  const DESKTOP_CLASSES = new Set(['Progman', 'WorkerW', 'Shell_TrayWnd', 'Shell_SecondaryTrayWnd']);

  // Returns the full-screen monitor rect (physical pixels) or null.
  check = () => {
    const hwnd = GetForegroundWindow();
    if (!hwnd) return null;
    const buf = Buffer.alloc(512);
    const n = GetClassNameW(hwnd, buf, 256);
    if (DESKTOP_CLASSES.has(buf.toString('utf16le', 0, n * 2))) return null;
    const r = {};
    if (!GetWindowRect(hwnd, r)) return null;
    const info = { cbSize: koffi.sizeof(MONITORINFO), rcMonitor: {}, rcWork: {}, dwFlags: 0 };
    if (!GetMonitorInfoW(MonitorFromWindow(hwnd, 2), info)) return null;
    const m = info.rcMonitor;
    const covers = r.left <= m.left && r.top <= m.top && r.right >= m.right && r.bottom >= m.bottom;
    return covers ? m : null;
  };

  // The active app window's rect (physical pixels) for the cat to perch on, or null.
  const IsIconic = user32.func('bool __stdcall IsIconic(void *hWnd)');
  const IsZoomed = user32.func('bool __stdcall IsZoomed(void *hWnd)');
  foreground = () => {
    const hwnd = GetForegroundWindow();
    if (!hwnd || IsIconic(hwnd)) return null;
    const buf = Buffer.alloc(512);
    const n = GetClassNameW(hwnd, buf, 256);
    const cls = buf.toString('utf16le', 0, n * 2);
    if (DESKTOP_CLASSES.has(cls)) return null;
    const r = {};
    if (!GetWindowRect(hwnd, r)) return null;
    return { ...r, maximized: IsZoomed(hwnd), cls };
  };

  // Is the (primary) mouse button held right now? Lets a drag end even if the cursor outruns the cat.
  const GetAsyncKeyState = user32.func('short __stdcall GetAsyncKeyState(int vKey)');
  const GetSystemMetrics = user32.func('int __stdcall GetSystemMetrics(int index)');
  leftDown = () => {
    const vk = GetSystemMetrics(23 /* SM_SWAPBUTTON */) ? 0x02 : 0x01;
    return (GetAsyncKeyState(vk) & 0x8000) !== 0;
  };
} catch (err) {
  console.warn('[workbuddy] full-screen detection unavailable:', err.message);
}

module.exports = {
  fullscreenMonitor: () => { try { return check(); } catch { return null; } },
  foregroundWindow: () => { try { return foreground(); } catch { return null; } },
  mouseButtonDown: () => { try { return leftDown(); } catch { return null; } },
};
