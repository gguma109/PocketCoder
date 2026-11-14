$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class PocketDxgiOutputs {
    [DllImport("dxgi.dll")] static extern int CreateDXGIFactory(ref Guid iid, out IntPtr factory);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Enumerate(IntPtr self, uint index, out IntPtr value);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int GetDescription(IntPtr self, IntPtr description);
    public class Output { public int Index, X, Y, Width, Height, Rotation, MonitorIndex = -1; }
    [StructLayout(LayoutKind.Sequential)] struct Rect { public int Left, Top, Right, Bottom; }
    delegate bool MonitorCallback(IntPtr monitor, IntPtr dc, ref Rect rect, IntPtr data);
    [DllImport("user32.dll")] static extern bool EnumDisplayMonitors(IntPtr dc, IntPtr clip, MonitorCallback callback, IntPtr data);
    // FFmpeg gfxcapture's monitor_idx counts monitors in EnumDisplayMonitors order.
    static List<Rect> Monitors() {
        var list = new List<Rect>();
        EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, delegate(IntPtr m, IntPtr dc, ref Rect r, IntPtr d) { list.Add(r); return true; }, IntPtr.Zero);
        return list;
    }
    static T Method<T>(IntPtr self, int slot) {
        IntPtr method = Marshal.ReadIntPtr(Marshal.ReadIntPtr(self), slot * IntPtr.Size);
        return (T)(object)Marshal.GetDelegateForFunctionPointer(method, typeof(T));
    }
    public static List<Output> Query() {
        Guid iid = new Guid("7b7166ec-21c7-44ae-b21a-c9ae321ae369");
        IntPtr factory = IntPtr.Zero, adapter = IntPtr.Zero;
        var result = new List<Output>();
        try {
            Marshal.ThrowExceptionForHR(CreateDXGIFactory(ref iid, out factory));
            // FFmpeg's default D3D11 device uses the default adapter (index zero).
            Marshal.ThrowExceptionForHR(Method<Enumerate>(factory, 7)(factory, 0, out adapter));
            for (uint index = 0; index < 32; index++) {
                IntPtr output;
                if (Method<Enumerate>(adapter, 7)(adapter, index, out output) < 0) break;
                IntPtr description = Marshal.AllocHGlobal(IntPtr.Size == 8 ? 96 : 92);
                try {
                    Marshal.ThrowExceptionForHR(Method<GetDescription>(output, 7)(output, description));
                    int x = Marshal.ReadInt32(description, 64), y = Marshal.ReadInt32(description, 68);
                    result.Add(new Output { Index = (int)index, X = x, Y = y,
                        Width = Marshal.ReadInt32(description, 72) - x,
                        Height = Marshal.ReadInt32(description, 76) - y,
                        Rotation = Marshal.ReadInt32(description, 84) });
                } finally { Marshal.FreeHGlobal(description); Marshal.Release(output); }
            }
        } finally { if (adapter != IntPtr.Zero) Marshal.Release(adapter); if (factory != IntPtr.Zero) Marshal.Release(factory); }
        var monitors = Monitors();
        foreach (var o in result) for (int i = 0; i < monitors.Count; i++)
            if (monitors[i].Left == o.X && monitors[i].Top == o.Y && monitors[i].Right - monitors[i].Left == o.Width && monitors[i].Bottom - monitors[i].Top == o.Height) { o.MonitorIndex = i; break; }
        return result;
    }
}
'@
@([PocketDxgiOutputs]::Query()) | ConvertTo-Json -Compress
