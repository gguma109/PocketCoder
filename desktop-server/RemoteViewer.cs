using System;
using System.IO;
using System.Net;
using System.Text;
using System.Drawing;
using System.Diagnostics;
using System.Collections.Generic;
using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

// A viewer never starts/stops the local server. Authentication is enforced by the remote server.
class RemoteViewer : Form {
    readonly Panel canvas=new Panel(); readonly FlowLayoutPanel bar=new FlowLayoutPanel();
    readonly Label state=new Label(); readonly Button claim=new Button();
    readonly JavaScriptSerializer json=new JavaScriptSerializer();
    readonly string host,client,root=AppDomain.CurrentDomain.BaseDirectory;
    readonly ConcurrentQueue<string> events=new ConcurrentQueue<string>();
    readonly AutoResetEvent wake=new AutoResetEvent(false);
    readonly HashSet<int> keys=new HashSet<int>(),buttons=new HashSet<int>();
    readonly object heldLock=new object(),requestLock=new object();
    readonly List<HttpWebRequest> streams=new List<HttpWebRequest>();
    readonly System.Windows.Forms.Timer timer=new System.Windows.Forms.Timer(), resizeTimer=new System.Windows.Forms.Timer();
    volatile bool stopped,mine,sound=true; bool armed=true; static RemoteViewer current; IntPtr videoWindow; volatile int display,width=1920,height=1080,generation; bool hardware=true;
    volatile string position; long lastMove; int queueSize; bool full=true,polling,joined;
    long revision=-1; Process player,audio; IntPtr keyboardHook,mouseHook; Hook keyboardProc,mouseProc;
    [DllImport("user32.dll")]static extern bool SetProcessDPIAware();
    public static void EnableDpi(){SetProcessDPIAware();}
    public static void Shutdown(){if(current!=null&&!current.IsDisposed)current.Close();}
    public static void Open(IWin32Window owner) {
        if(current!=null&&!current.IsDisposed){current.Activate();return;}
        using(var dialog=new Form()) {
            dialog.Text="다른 PC 보기 · Tailscale";dialog.ClientSize=new Size(440,145);dialog.StartPosition=FormStartPosition.CenterParent;
            var label=new Label{Text="원격 PC의 Tailscale IPv4:포트 (같은 계정)",Left=15,Top=12,Width=410};
            var box=new TextBox{Left=15,Top=42,Width=410,Text=LoadSetting("address","")};
            var connect=new Button{Text="연결",Left=315,Top=87,Width=110,DialogResult=DialogResult.OK};
            dialog.Controls.AddRange(new Control[]{label,box,connect});dialog.AcceptButton=connect;
            if(dialog.ShowDialog(owner)!=DialogResult.OK)return;
            try{string address=ValidateAddress(box.Text); var health=ReadJson(address,"/health",null);
                if(Convert.ToString(health["auth"])!="tailscale" || new Version(Convert.ToString(health["version"]))<new Version("0.8.0"))throw new Exception("Tailscale 모드의 0.8.0 이상 서버가 필요합니다.");
                if(Convert.ToString(health["video"])!="h264")throw new Exception("원격 PC의 H.264 인코더를 사용할 수 없습니다.");
                Save("address",address);current=new RemoteViewer(address,health);current.Show();
            }catch(Exception e){MessageBox.Show(owner,e.Message,"연결 실패");}
        }
    }
    static string SettingsPath {get{return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"PocketCoder","viewer.json");}}
    static string LoadSetting(string key,string fallback){try{var d=new JavaScriptSerializer().Deserialize<Dictionary<string,string>>(File.ReadAllText(SettingsPath));return d.ContainsKey(key)?d[key]:fallback;}catch{return fallback;}}
    static void Save(string key,string value){var d=new Dictionary<string,string>();try{d=new JavaScriptSerializer().Deserialize<Dictionary<string,string>>(File.ReadAllText(SettingsPath));}catch{} d[key]=value;Directory.CreateDirectory(Path.GetDirectoryName(SettingsPath));File.WriteAllText(SettingsPath,new JavaScriptSerializer().Serialize(d));}
    internal static string ValidateAddress(string value){
        var parts=value.Trim().Split(':');IPAddress ip;int port=8765;
        if(parts.Length>2||!IPAddress.TryParse(parts[0],out ip)||ip.AddressFamily!=System.Net.Sockets.AddressFamily.InterNetwork)throw new Exception("Tailscale IPv4:포트를 입력하세요.");
        var bytes=ip.GetAddressBytes();if(bytes[0]!=100||bytes[1]<64||bytes[1]>127)throw new Exception("Tailscale 주소(100.64.0.0~100.127.255.255)만 연결합니다.");
        if(parts.Length==2&&(!int.TryParse(parts[1],out port)||port<1||port>65535))throw new Exception("포트가 올바르지 않습니다.");return ip+":"+port;
    }
    static HttpWebRequest Request(string host,string path,string body){var r=(HttpWebRequest)WebRequest.Create("http://"+host+path);r.Proxy=null;r.AllowAutoRedirect=false;r.Timeout=4000;r.ReadWriteTimeout=8000;r.ServicePoint.ConnectionLimit=16;r.KeepAlive=true;r.ServicePoint.Expect100Continue=false;
        if(body!=null){r.Method="POST";r.ContentType="application/x-www-form-urlencoded";var data=Encoding.UTF8.GetBytes(body);r.ContentLength=data.Length;using(var output=r.GetRequestStream())output.Write(data,0,data.Length);}return r;}
    static Dictionary<string,object> ReadJson(string host,string path,string body){using(var response=Request(host,path,body).GetResponse())using(var input=new StreamReader(response.GetResponseStream()))return new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(input.ReadToEnd());}
    string Fields(string action){return "client="+client+"&action="+action+"&label="+Uri.EscapeDataString(Environment.MachineName);}
    RemoteViewer(string address,Dictionary<string,object> health){
        host=address;client=LoadSetting("client","");Guid id;if(!Guid.TryParse(client,out id)){client=Guid.NewGuid().ToString("N");Save("client",client);}
        Text="PocketCoder · 원격 PC · Ctrl+Alt+Shift+Q로 조작 해제";BackColor=Color.Black;ClientSize=new Size(1280,720);MinimumSize=new Size(640,360);
        canvas.Dock=DockStyle.Fill;canvas.BackColor=Color.Black;Controls.Add(canvas);
        bar.BackColor=Color.FromArgb(235,240,245);bar.Height=42;bar.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;bar.Width=ClientSize.Width;bar.WrapContents=false;bar.AutoScroll=true;Controls.Add(bar);bar.BringToFront();
        state.AutoSize=true;state.Padding=new Padding(4,10,4,0);bar.Controls.Add(state);
        claim.Text="조작 시작 / 가져오기";claim.Width=145;claim.Height=34;claim.Click+=async delegate{armed=true;await Control("claim");};bar.Controls.Add(claim);
        int count=Convert.ToInt32(health["displays"]);for(int i=0;i<count;i++){int index=i;Add("모니터 "+(i+1),async delegate{if(!mine)return;ReleaseHeld();try{var result=await Task.Run(()=>ReadJson(host,"/display","client="+client+"&index="+index));display=Convert.ToInt32(result["display"]);revision=Convert.ToInt64(result["displayRevision"]);Interlocked.Increment(ref generation);StopProcess(ref player);}catch(Exception e){state.Text=e.Message;}});}
        Add("창 / 전체",delegate{full=!full;ApplyWindow();});Add("소리 켬 / 끔",delegate{sound=!sound;StopProcess(ref audio);if(sound)Task.Run((Action)AudioLoop);});Add("연결 종료",delegate{Close();});
        display=Convert.ToInt32(health["display"]);revision=Convert.ToInt64(health["displayRevision"]);
        keyboardProc=Keyboard;mouseProc=Mouse;Shown+=async delegate{ApplyWindow();await Control("join");joined=true;Task.Run((Action)InputLoop);Task.Run((Action)VideoLoop);Task.Run((Action)AudioLoop);timer.Start();};
        resizeTimer.Interval=250;resizeTimer.Tick+=delegate{resizeTimer.Stop();if(joined&&!stopped){Interlocked.Increment(ref generation);StopProcess(ref player);}};
        timer.Interval=1000;timer.Tick+=async delegate{var pointer=PointToClient(Cursor.Position);bar.Visible=!mine||pointer.Y<48;await Poll();};
        Deactivate+=delegate{SuspendInput();};Activated+=delegate{if(mine&&armed)InstallHooks();};
        FormClosing+=delegate{stopped=true;mine=false;timer.Stop();resizeTimer.Stop();Unhook();ReleaseHeld();wake.Set();lock(requestLock){foreach(var r in streams)r.Abort();}StopProcess(ref player);StopProcess(ref audio);try{ReadJson(host,"/control",Fields("release"));}catch{}};
    }
    void Add(string text,EventHandler click){var b=new Button{Text=text,Height=34,AutoSize=true};b.Click+=click;bar.Controls.Add(b);}
    void ApplyWindow(){FormBorderStyle=full?FormBorderStyle.None:FormBorderStyle.Sizable;WindowState=full?FormWindowState.Maximized:FormWindowState.Normal;}
    async Task Control(string action){try{var s=await Task.Run(()=>ReadJson(host,"/control",Fields(action)));SetControl(Convert.ToBoolean(s["mine"]));}catch(Exception e){state.Text=e.Message;SuspendInput();}}
    void SetControl(bool value){if(mine&&!value)SuspendInput();mine=value;state.Text=mine?"조작 중 · Ctrl+Alt+Shift+Q 탈출":"보기 전용";if(mine&&armed&&ContainsFocus)InstallHooks();}
    async Task Poll(){if(polling||stopped||!joined)return;polling=true;try{var s=await Task.Run(()=>ReadJson(host,"/status?client="+client,null));SetControl(Convert.ToBoolean(((Dictionary<string,object>)s["control"])["mine"]));
        long next=Convert.ToInt64(s["displayRevision"]);display=Convert.ToInt32(s["display"]);if(next!=revision){revision=next;Interlocked.Increment(ref generation);StopProcess(ref player);}
    }catch{SetControl(false);state.Text="연결 확인 중…";}finally{polling=false;}}
    void Send(string body){if(stopped)return;if(Interlocked.Increment(ref queueSize)>512){Interlocked.Decrement(ref queueSize);BeginInvoke((Action)delegate{SuspendInput();Task.Run(()=>{try{ReadJson(host,"/control",Fields("release"));}catch{}});});return;}events.Enqueue("client="+client+"&screen="+display+"&"+body);wake.Set();}
    static HttpWebRequest InputRequest(string address){var r=Request(address,"/input-stream",null);r.Method="POST";r.SendChunked=true;r.AllowWriteStreamBuffering=false;r.ContentType="application/x-www-form-urlencoded";r.ReadWriteTimeout=5000;return r;}
    static string Line(Stream input){var b=new StringBuilder();int c;while((c=input.ReadByte())!=-1){if(c==10)return b.ToString().TrimEnd('\r');if(b.Length>32768)throw new IOException("Header too large");b.Append((char)c);}throw new EndOfStreamException();}
    static void InputTransportSelfTest(){
        var listener=new System.Net.Sockets.TcpListener(IPAddress.Loopback,0);listener.Start();
        try{string address="127.0.0.1:"+((IPEndPoint)listener.LocalEndpoint).Port;
            var peer=Task.Run(()=>{using(var c=listener.AcceptTcpClient())using(var input=c.GetStream()){
                input.ReadTimeout=5000;string first=Line(input);if(!first.StartsWith("POST /input-stream "))throw new Exception("Input path");while(Line(input)!=""){}
                var body=new MemoryStream();while(true){int n=Convert.ToInt32(Line(input).Split(';')[0],16);if(n==0){Line(input);break;}var bytes=Bytes(input,n);body.Write(bytes,0,n);if(Line(input)!="")throw new Exception("Chunk framing");}
                var reply=Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}");input.Write(reply,0,reply.Length);
                return Encoding.UTF8.GetString(body.ToArray());
            }});
            var request=InputRequest(address);using(var output=request.GetRequestStream()){for(int i=0;i<100;i++){var bytes=Encoding.UTF8.GetBytes("client=test&screen=0&type=keydown&vk=255\n");output.Write(bytes,0,bytes.Length);output.Flush();}}
            using(var response=request.GetResponse()){}if(!peer.Wait(5000)||peer.Result.Split(new[]{'\n'},StringSplitOptions.RemoveEmptyEntries).Length!=100)throw new Exception("Persistent input framing");
        }finally{listener.Stop();}
    }
    void InputLoop(){
        while(!stopped){HttpWebRequest r=null;
            try{r=InputRequest(host);
                lock(requestLock){if(stopped)return;streams.Add(r);}
                using(var output=r.GetRequestStream()){
                    while(!stopped){string item;
                        if(!events.TryDequeue(out item)){item=Interlocked.Exchange(ref position,null);if(item==null){wake.WaitOne(8);continue;}}
                        else Interlocked.Decrement(ref queueSize);
                        var bytes=Encoding.UTF8.GetBytes(item+"\n");output.Write(bytes,0,bytes.Length);output.Flush();
                    }
                }
            }catch{if(!stopped&&IsHandleCreated)BeginInvoke((Action)delegate{armed=false;SuspendInput();mine=false;state.Text="입력 재연결 · 조작 시작을 누르세요";Task.Run(()=>{try{ReadJson(host,"/control",Fields("release"));}catch{}});});}
            finally{if(r!=null)Forget(r);}if(!stopped)Thread.Sleep(300);
        }
    }
    void ReleaseHeld(){lock(heldLock){foreach(var key in keys)Send("type=keyup&vk="+key);keys.Clear();foreach(var button in buttons)Send("type=up&button="+ButtonName(button));buttons.Clear();}Interlocked.Exchange(ref position,null);}
    void SuspendInput(){Unhook();ReleaseHeld();}
    static bool EscapeChord(int vk,HashSet<int> held){return vk==81&&(held.Contains(162)||held.Contains(163)||held.Contains(17))&&(held.Contains(164)||held.Contains(165)||held.Contains(18))&&(held.Contains(160)||held.Contains(161)||held.Contains(16));}
    static string ButtonName(int value){return value==1?"left":value==2?"right":"middle";}
    bool ActiveInput {get{return mine&&armed&&!stopped&&GetForegroundWindow()==Handle;}}
    void InstallHooks(){if(keyboardHook!=IntPtr.Zero||stopped)return;keyboardHook=SetWindowsHookEx(13,keyboardProc,GetModuleHandle(null),0);mouseHook=SetWindowsHookEx(14,mouseProc,GetModuleHandle(null),0);if(keyboardHook==IntPtr.Zero||mouseHook==IntPtr.Zero){Unhook();state.Text="입력 훅을 시작하지 못했습니다.";}}
    void Unhook(){if(keyboardHook!=IntPtr.Zero)UnhookWindowsHookEx(keyboardHook);if(mouseHook!=IntPtr.Zero)UnhookWindowsHookEx(mouseHook);keyboardHook=mouseHook=IntPtr.Zero;Cursor.Current=Cursors.Default;}
    IntPtr Keyboard(int code,IntPtr message,IntPtr data){if(code>=0&&ActiveInput){int vk=Marshal.ReadInt32(data),flags=Marshal.ReadInt32(data,8);if((flags&16)!=0)return CallNextHookEx(IntPtr.Zero,code,message,data);
        bool down=message.ToInt32()==256||message.ToInt32()==260;
        lock(heldLock){if(down&&EscapeChord(vk,keys)){BeginInvoke((Action)delegate{armed=false;SuspendInput();mine=false;Task.Run(()=>{try{ReadJson(host,"/control",Fields("release"));}catch{}});state.Text="보기 전용 · 로컬 복귀";});return new IntPtr(1);}if(down)keys.Add(vk);else keys.Remove(vk);}
        Send("type="+(down?"keydown":"keyup")+"&vk="+vk+"&extended="+(((flags&1)!=0)?"1":"0"));return new IntPtr(1);
    }return CallNextHookEx(IntPtr.Zero,code,message,data);}
    IntPtr Mouse(int code,IntPtr message,IntPtr data){if(code>=0&&ActiveInput){var p=(MouseData)Marshal.PtrToStructure(data,typeof(MouseData));if((p.flags&1)!=0)return CallNextHookEx(IntPtr.Zero,code,message,data);if(bar.Visible&&bar.RectangleToScreen(bar.ClientRectangle).Contains(p.point)){if(buttons.Count>0)ReleaseHeld();return CallNextHookEx(IntPtr.Zero,code,message,data);}Point local=canvas.PointToClient(p.point);int x,y;
        bool inside=MapPoint(local,canvas.ClientSize,width,height,out x,out y);if(!inside){Cursor.Current=Cursors.Default;if(buttons.Count>0)ReleaseHeld();return CallNextHookEx(IntPtr.Zero,code,message,data);}Cursor.Current=null;
        int msg=message.ToInt32();string pos="client="+client+"&screen="+display+"&type=pos&x="+x+"&y="+y;
        if(msg==512){long now=Stopwatch.GetTimestamp();if((now-lastMove)*1000/Stopwatch.Frequency>=8){lastMove=now;Interlocked.Exchange(ref position,pos);wake.Set();}return new IntPtr(1);}
        int b=msg==513||msg==514?1:msg==516||msg==517?2:msg==519||msg==520?3:0;
        if(b!=0){Interlocked.Exchange(ref position,null);bool down=msg==513||msg==516||msg==519;Send("type=pos&x="+x+"&y="+y);lock(heldLock){if(down)buttons.Add(b);else buttons.Remove(b);}Send("type="+(down?"down":"up")+"&button="+ButtonName(b));return new IntPtr(1);}
        if(msg==522){int delta=(short)((p.mouseData>>16)&65535);Send("type=wheel&amount="+Math.Max(-15,Math.Min(15,-delta/120)));return new IntPtr(1);}
    }return CallNextHookEx(IntPtr.Zero,code,message,data);}
    internal static bool MapPoint(Point p,Size area,int w,int h,out int x,out int y){double scale=Math.Min((double)area.Width/w,(double)area.Height/h);double left=(area.Width-w*scale)/2,top=(area.Height-h*scale)/2;x=y=0;if(scale<=0||p.X<left||p.Y<top||p.X>=left+w*scale||p.Y>=top+h*scale)return false;x=(int)Math.Round((p.X-left)*10000/Math.Max(1,w*scale-1));y=(int)Math.Round((p.Y-top)*10000/Math.Max(1,h*scale-1));x=Math.Max(0,Math.Min(10000,x));y=Math.Max(0,Math.Min(10000,y));return true;}
    static int Int(Stream s){var b=Bytes(s,4);return (b[0]<<24)|(b[1]<<16)|(b[2]<<8)|b[3];}
    static byte[] Bytes(Stream s,int count){var b=new byte[count];int n=0;while(n<count){int got=s.Read(b,n,count-n);if(got==0)throw new EndOfStreamException();n+=got;}return b;}
    HttpWebRequest StreamRequest(string path){var r=Request(host,path,null);lock(requestLock){if(stopped)throw new OperationCanceledException();streams.Add(r);}return r;}
    void Forget(HttpWebRequest r){lock(requestLock)streams.Remove(r);r.Abort();}
    Process Play(string args){var path=Path.Combine(root,"bin","ffplay.exe");if(!File.Exists(path))throw new IOException("설치본의 bin\\ffplay.exe가 없습니다. 다시 설치하세요.");var p=new Process();p.StartInfo=new ProcessStartInfo(path,args){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true};p.Start();return p;}
    static void StopProcess(ref Process slot){var p=Interlocked.Exchange(ref slot,null);if(p!=null){try{if(!p.HasExited)p.Kill();}catch{}try{p.Dispose();}catch{}}}
    void VideoLoop(){while(!stopped){HttpWebRequest r=null;Process p=null;try{int gen=generation;r=StreamRequest("/video?fps=60&quality=native&mbps=60");using(var response=r.GetResponse())using(var input=response.GetResponseStream()){
        if(Int(input)!=0x50435631)throw new IOException("잘못된 영상 헤더");width=Int(input);height=Int(input);int fps=Int(input);if(width<1||width>16384||height<1||height>16384||fps<1||fps>240)throw new IOException("잘못된 영상 크기");
        Size target=Size.Empty;Invoke((Action)delegate{target=canvas.ClientSize;});p=Play((hardware?"-hwaccel d3d11va ":"")+"-loglevel error -noborder -x "+target.Width+" -y "+target.Height+" -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -sync ext -f h264 -i pipe:0");player=p;
        Process embedded=p;Task.Run(()=>{for(int i=0;i<100&&!stopped&&!embedded.HasExited;i++){embedded.Refresh();if(embedded.MainWindowHandle!=IntPtr.Zero){BeginInvoke((Action)delegate{if(stopped||embedded.HasExited)return;videoWindow=embedded.MainWindowHandle;SetParent(videoWindow,canvas.Handle);SetWindowLong(embedded.MainWindowHandle,-16,0x50000000);MoveWindow(embedded.MainWindowHandle,0,0,canvas.Width,canvas.Height,true);SetWindowPos(embedded.MainWindowHandle,IntPtr.Zero,0,0,0,0,0x0013);Activate();});break;}Thread.Sleep(50);}});
        while(!stopped&&gen==generation){int size=Int(input);Bytes(input,8);if(size<1||size>8388608)throw new IOException("영상 패킷 크기 오류");var b=Bytes(input,size);p.StandardInput.BaseStream.Write(b,0,b.Length);p.StandardInput.BaseStream.Flush();}
    }}catch(Exception e){if(!stopped&&IsHandleCreated)BeginInvoke((Action)delegate{state.Text="영상 재연결: "+e.Message;});}finally{if(r!=null)Forget(r);if(p!=null){try{if(p.HasExited&&p.ExitCode!=0)hardware=false;if(!p.HasExited)p.Kill();p.Dispose();}catch{}}if(player==p)player=null;}if(!stopped)Thread.Sleep(300);}}
    void AudioLoop(){HttpWebRequest r=null;Process p=null;try{r=StreamRequest("/audio");using(var response=r.GetResponse())using(var input=response.GetResponseStream()){
        if(Int(input)!=0x50434131)throw new IOException("잘못된 오디오 헤더");int rate=Int(input),channels=Int(input),bits=Int(input);if(rate<8000||rate>192000||channels!=2||bits!=16)throw new IOException("지원하지 않는 오디오 형식");
        p=Play("-loglevel error -nodisp -autoexit -fflags nobuffer -f s16le -ar "+rate+" -ac "+channels+" -i pipe:0");audio=p;
        while(!stopped&&sound){int size=Int(input);if(size<4||size>262144||size%4!=0)throw new IOException("오디오 패킷 오류");var b=Bytes(input,size);p.StandardInput.BaseStream.Write(b,0,size);p.StandardInput.BaseStream.Flush();}
    }}catch{}finally{if(r!=null)Forget(r);if(p!=null){try{if(!p.HasExited)p.Kill();p.Dispose();}catch{}}if(audio==p)audio=null;}}
    protected override void OnResize(EventArgs e){base.OnResize(e);if(resizeTimer!=null&&joined&&!stopped){resizeTimer.Stop();resizeTimer.Start();}try{if(videoWindow!=IntPtr.Zero)MoveWindow(videoWindow,0,0,canvas.Width,canvas.Height,true);}catch{}}
    internal static void SelfTest(){InputTransportSelfTest();if(!EscapeChord(81,new HashSet<int>{162,164,160})||EscapeChord(81,new HashSet<int>{162,164})||EscapeChord(65,new HashSet<int>{162,164,160}))throw new Exception("Escape chord");if(ValidateAddress("100.64.0.1")!="100.64.0.1:8765")throw new Exception("Address");bool rejected=false;try{ValidateAddress("8.8.8.8");}catch{rejected=true;}if(!rejected)throw new Exception("Public address accepted");int x,y;if(MapPoint(new Point(10,10),new Size(1000,1000),1920,1080,out x,out y))throw new Exception("Letterbox");if(!MapPoint(new Point(500,500),new Size(1000,1000),1920,1080,out x,out y)||Math.Abs(x-5000)>20||Math.Abs(y-5000)>20)throw new Exception("Coordinates");using(var m=new MemoryStream(new byte[]{0x50,0x43,0x56,0x31})){if(Int(m)!=0x50435631)throw new Exception("Endian");}Console.WriteLine("PASS: viewer address / letterbox coordinates / wire header");}
    delegate IntPtr Hook(int code,IntPtr message,IntPtr data);
    [StructLayout(LayoutKind.Sequential)]struct MouseData{public Point point;public uint mouseData,flags,time;public UIntPtr extra;}
    [DllImport("user32.dll")]static extern IntPtr SetWindowsHookEx(int id,Hook callback,IntPtr module,uint thread);
    [DllImport("user32.dll")]static extern bool UnhookWindowsHookEx(IntPtr hook);
    [DllImport("user32.dll")]static extern IntPtr CallNextHookEx(IntPtr hook,int code,IntPtr message,IntPtr data);
    [DllImport("user32.dll")]static extern IntPtr GetForegroundWindow();
    [DllImport("kernel32.dll",CharSet=CharSet.Auto)]static extern IntPtr GetModuleHandle(string name);
    [DllImport("user32.dll")]static extern IntPtr SetParent(IntPtr child,IntPtr parent);
    [DllImport("user32.dll")]static extern int SetWindowLong(IntPtr handle,int index,int value);
    [DllImport("user32.dll")]static extern bool MoveWindow(IntPtr handle,int x,int y,int w,int h,bool repaint);
    [DllImport("user32.dll")]static extern bool SetWindowPos(IntPtr h,IntPtr after,int x,int y,int w,int height,uint flags);
}
