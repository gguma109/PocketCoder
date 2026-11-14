using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Web.Script.Serialization;

// Read-only desktop cursor capture. No input injection or network listener.
class PocketCoderCursor {
    [StructLayout(LayoutKind.Sequential)]struct Info {public int size,flags;public IntPtr cursor;public Point point;}
    [StructLayout(LayoutKind.Sequential)]struct IconInfo {public bool icon;public uint x,y;public IntPtr mask,color;}
    [DllImport("user32.dll")]static extern bool GetCursorInfo(ref Info info);
    [DllImport("user32.dll")]static extern bool GetIconInfo(IntPtr cursor,out IconInfo info);
    [DllImport("user32.dll")]static extern bool DrawIconEx(IntPtr dc,int x,int y,IntPtr icon,int w,int h,int step,IntPtr brush,int flags);
    [DllImport("gdi32.dll")]static extern bool DeleteObject(IntPtr handle);
    [DllImport("user32.dll")]static extern bool SetProcessDPIAware();
    static object Shape(IntPtr handle) {
        IconInfo ii;if(!GetIconInfo(handle,out ii))throw new IOException("Cursor shape unavailable");
        try {
            int w,h;using(var source=Image.FromHbitmap(ii.color!=IntPtr.Zero?ii.color:ii.mask)){w=source.Width;h=source.Height/(ii.color==IntPtr.Zero?2:1);}
            if(w<1||h<1||w>256||h>256)throw new IOException("Cursor shape too large");
            using(var image=new Bitmap(w,h,PixelFormat.Format32bppArgb)){
                using(var g=Graphics.FromImage(image)){IntPtr dc=g.GetHdc();try{if(!DrawIconEx(dc,0,0,handle,w,h,0,IntPtr.Zero,3))throw new IOException("Cursor drawing failed");}finally{g.ReleaseHdc(dc);}}
                using(var data=new MemoryStream()){image.Save(data,ImageFormat.Png);return new {png=Convert.ToBase64String(data.ToArray()),hotX=ii.x,hotY=ii.y};}
            }
        }finally{if(ii.mask!=IntPtr.Zero)DeleteObject(ii.mask);if(ii.color!=IntPtr.Zero)DeleteObject(ii.color);}
    }
    [DllImport("user32.dll")]static extern IntPtr LoadCursor(IntPtr instance,int id);
    static void Main(string[] args) {
        SetProcessDPIAware();var json=new JavaScriptSerializer();
        if(args.Length>0&&args[0]=="--self-test"){foreach(int id in new[]{32512,32513,32649}){
            var data=json.Deserialize<System.Collections.Generic.Dictionary<string,object>>(json.Serialize(Shape(LoadCursor(IntPtr.Zero,id))));
            using(var bytes=new MemoryStream(Convert.FromBase64String(Convert.ToString(data["png"]))))using(var image=new Bitmap(bytes)){
                int visible=0;for(int y=0;y<image.Height;y++)for(int x=0;x<image.Width;x++)if(image.GetPixel(x,y).A>0)visible++;
                if(visible==0)throw new Exception("Cursor image is transparent: "+id);
            }
        }Console.WriteLine("Cursor arrow/I-beam/hand PNG and hotspot PASS");return;}
        IntPtr previous=IntPtr.Zero;long serial=0;
        while(true){try{var info=new Info();info.size=Marshal.SizeOf(info);if(GetCursorInfo(ref info)){
            object shape=null;if(info.cursor!=IntPtr.Zero&&info.cursor!=previous){shape=Shape(info.cursor);previous=info.cursor;serial++;}
            Console.WriteLine(json.Serialize(new {x=info.point.X,y=info.point.Y,visible=(info.flags&1)!=0,shapeId=serial,shape=shape}));Console.Out.Flush();
        }}catch(IOException){return;}catch{previous=IntPtr.Zero;}Thread.Sleep(16);}
    }
}
