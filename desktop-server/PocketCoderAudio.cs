using System;
using System.IO;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

// WASAPI render-loopback only. No microphone, device selection, files, or remote command arguments.
class PocketCoderAudio {
    static void Check(int hr) { Marshal.ThrowExceptionForHR(hr); }
    static void Int32BE(Stream output,int value) { output.WriteByte((byte)(value>>24)); output.WriteByte((byte)(value>>16)); output.WriteByte((byte)(value>>8)); output.WriteByte((byte)value); }
    static short Pcm(float value) { if(Single.IsNaN(value))return 0; return (short)(Math.Max(-1,Math.Min(1,value))*32767); }
    static float Sample(byte[] data,int offset,int bits,bool floating) {
        if(floating && bits==32)return BitConverter.ToSingle(data,offset);
        if(bits==16)return BitConverter.ToInt16(data,offset)/32768f;
        if(bits==24) { int n=data[offset]|(data[offset+1]<<8)|(data[offset+2]<<16); if((n&0x800000)!=0)n|=unchecked((int)0xff000000); return n/8388608f; }
        if(bits==32)return (float)(BitConverter.ToInt32(data,offset)/2147483648.0);
        throw new InvalidDataException("Unsupported render format");
    }
    static byte[] ConvertPcm(byte[] source,int frames,int channels,int bits,bool floating) {
        int sampleBytes=bits/8; var result=new byte[frames*4];
        for(int i=0;i<frames;i++) {
            int offset=i*channels*sampleBytes;
            float left=Sample(source,offset,bits,floating);
            float right=channels>1?Sample(source,offset+sampleBytes,bits,floating):left;
            if(channels>2) { float center=Sample(source,offset+2*sampleBytes,bits,floating)*.707f; left+=center;right+=center; }
            for(int c=4;c<channels;c++) { float surround=Sample(source,offset+c*sampleBytes,bits,floating)*.5f; if(c%2==0)left+=surround;else right+=surround; }
            short l=Pcm(left),r=Pcm(right); result[i*4]=(byte)l;result[i*4+1]=(byte)(l>>8);result[i*4+2]=(byte)r;result[i*4+3]=(byte)(r>>8);
        }
        return result;
    }
    [MTAThread] static int Main(string[] args) {
        if(args.Length==1 && args[0]=="--self-test") {
            if(Pcm(2)!=32767 || Pcm(-2)!=-32767 || Pcm(Single.NaN)!=0) return 1;
            var data=ConvertPcm(BitConverter.GetBytes(.5f),1,1,32,true);
            if(BitConverter.ToInt16(data,0)!=16383 || BitConverter.ToInt16(data,2)!=16383)return 1;
            Console.WriteLine("WASAPI PCM conversion PASS");return 0;
        }
        IAudioClient client=null; IntPtr format=IntPtr.Zero; object device=null,captureObject=null,enumerator=null;
        try {
            enumerator=new MMDeviceEnumerator(); IMMDevice endpoint;
            Check(((IMMDeviceEnumerator)enumerator).GetDefaultAudioEndpoint(0,0,out endpoint));device=endpoint;
            Guid audioId=typeof(IAudioClient).GUID;object activated;
            Check(endpoint.Activate(ref audioId,23,IntPtr.Zero,out activated));client=(IAudioClient)activated;
            Check(client.GetMixFormat(out format));
            int tag=(ushort)Marshal.ReadInt16(format,0),channels=(ushort)Marshal.ReadInt16(format,2),rate=Marshal.ReadInt32(format,4),bits=(ushort)Marshal.ReadInt16(format,14);
            bool floating=tag==3 || (tag==65534 && Marshal.ReadInt32(format,24)==3);
            if(channels<1||channels>8||rate<8000||rate>192000 || (bits!=16&&bits!=24&&bits!=32))throw new InvalidDataException("Unsupported render mix");
            Guid session=Guid.Empty; Check(client.Initialize(0,0x20000,1000000,0,format,ref session));
            Guid captureId=typeof(IAudioCaptureClient).GUID;Check(client.GetService(ref captureId,out captureObject));var capture=(IAudioCaptureClient)captureObject;
            var output=Console.OpenStandardOutput(); Int32BE(output,0x50434131);Int32BE(output,rate);Int32BE(output,2);Int32BE(output,16);output.Flush();
            Check(client.Start()); var clock=Stopwatch.StartNew();long last=0;
            while(true) {
                uint frames;Check(capture.GetNextPacketSize(out frames));
                if(frames>0) {
                    IntPtr buffer;uint flags;ulong position,time;Check(capture.GetBuffer(out buffer,out frames,out flags,out position,out time));
                    byte[] pcm;
                    try {
                        if((flags&2)!=0)pcm=new byte[checked((int)frames*4)];
                        else { var raw=new byte[checked((int)frames*channels*bits/8)];Marshal.Copy(buffer,raw,0,raw.Length);pcm=ConvertPcm(raw,(int)frames,channels,bits,floating); }
                    } finally { Check(capture.ReleaseBuffer(frames)); }
                    Int32BE(output,pcm.Length);output.Write(pcm,0,pcm.Length);output.Flush();last=clock.ElapsedMilliseconds;
                } else if(clock.ElapsedMilliseconds-last>=40) {
                    byte[] silence=new byte[rate/50*4]; Int32BE(output,silence.Length);output.Write(silence,0,silence.Length);output.Flush();last=clock.ElapsedMilliseconds;
                } else Thread.Sleep(3);
            }
        } catch(IOException) { return 0; }
        catch(Exception e) { Console.Error.WriteLine("Windows playback capture unavailable: "+e.GetType().Name+" "+e.Message);return 1; }
        finally { if(client!=null)try{client.Stop();}catch{} if(format!=IntPtr.Zero)Marshal.FreeCoTaskMem(format);if(captureObject!=null)Marshal.ReleaseComObject(captureObject);if(client!=null)Marshal.ReleaseComObject(client);if(device!=null)Marshal.ReleaseComObject(device);if(enumerator!=null)Marshal.ReleaseComObject(enumerator); }
    }
    [ComImport,Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumerator {}
    [ComImport,Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IMMDeviceEnumerator {
        [PreserveSig]int EnumAudioEndpoints(int flow,uint mask,out IntPtr devices);
        [PreserveSig]int GetDefaultAudioEndpoint(int flow,int role,out IMMDevice device);
    }
    [ComImport,Guid("D666063F-1587-4E43-81F1-B948E807363F"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IMMDevice {
        [PreserveSig]int Activate(ref Guid id,int context,IntPtr parameters,[MarshalAs(UnmanagedType.IUnknown)]out object instance);
    }
    [ComImport,Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IAudioClient {
        [PreserveSig]int Initialize(int mode,uint flags,long duration,long periodicity,IntPtr format,ref Guid session);
        [PreserveSig]int GetBufferSize(out uint frames); [PreserveSig]int GetStreamLatency(out long latency); [PreserveSig]int GetCurrentPadding(out uint padding);
        [PreserveSig]int IsFormatSupported(int mode,IntPtr format,out IntPtr closest); [PreserveSig]int GetMixFormat(out IntPtr format);
        [PreserveSig]int GetDevicePeriod(out long normal,out long minimum); [PreserveSig]int Start(); [PreserveSig]int Stop(); [PreserveSig]int Reset();
        [PreserveSig]int SetEventHandle(IntPtr handle); [PreserveSig]int GetService(ref Guid id,[MarshalAs(UnmanagedType.IUnknown)]out object service);
    }
    [ComImport,Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IAudioCaptureClient {
        [PreserveSig]int GetBuffer(out IntPtr data,out uint frames,out uint flags,out ulong position,out ulong time);
        [PreserveSig]int ReleaseBuffer(uint frames); [PreserveSig]int GetNextPacketSize(out uint frames);
    }
}
