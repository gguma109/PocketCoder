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
    readonly Panel canvas=new InputSurface(); readonly FlowLayoutPanel bar=new FlowLayoutPanel();
    readonly Label state=new Label(); readonly Button claim=new Button(),menuButton=new Button(),displayMode=new Button();
    readonly HashSet<int> localShortcutUps=new HashSet<int>();
    string monitorHotkey=LoadSetting("monitorHotkey","Ctrl+Alt");int monitorCount;
    readonly JavaScriptSerializer json=new JavaScriptSerializer();
    readonly string host,client,root=AppDomain.CurrentDomain.BaseDirectory;
    readonly ConcurrentQueue<string> events=new ConcurrentQueue<string>();
    readonly AutoResetEvent wake=new AutoResetEvent(false);
    readonly HashSet<int> keys=new HashSet<int>(),buttons=new HashSet<int>();
    readonly object heldLock=new object(),requestLock=new object();
    readonly List<HttpWebRequest> streams=new List<HttpWebRequest>();
    readonly System.Windows.Forms.Timer timer=new System.Windows.Forms.Timer(), resizeTimer=new System.Windows.Forms.Timer();
    volatile bool stopped,mine,sound=true; bool armed=true; static RemoteViewer current; IntPtr videoWindow; volatile int display,width=1920,height=1080,generation; bool hardware=!String.Equals(LoadSetting("compatVideo","false"),"true",StringComparison.OrdinalIgnoreCase);
    volatile string position; long lastMove; int queueSize; bool full=true,polling,joined;
    bool switching; int controlEpoch; HttpWebRequest videoRequest;
    long revision=-1; Process player,audio; IntPtr keyboardHook,mouseHook; Hook keyboardProc,mouseProc; int mouseObserved,mouseForwarded; int diagnosticTick;
    [DllImport("user32.dll")]static extern bool SetProcessDPIAware();
    public static void EnableDpi(){SetProcessDPIAware();}
    public static void Shutdown(){if(current!=null&&!current.IsDisposed)current.Close();}
    public static void Open(IWin32Window owner) {
        if(current!=null&&!current.IsDisposed){current.Activate();return;}
        using(var dialog=new Form()) {
            dialog.AutoScaleMode=AutoScaleMode.None;dialog.Font=new Font("Malgun Gothic",10);dialog.Text="다른 PC 보기 · Tailscale";dialog.ClientSize=new Size(440,160);dialog.StartPosition=FormStartPosition.CenterParent;
            var label=new Label{Text="원격 PC의 Tailscale IPv4:포트 (같은 계정)",Left=15,Top=12,Width=410,Height=28};
            var box=new TextBox{Left=15,Top=42,Width=410,Text=LoadSetting("address","")};
            var connect=new Button{Text="연결",Left=315,Top=95,Width=110,Height=42,DialogResult=DialogResult.OK};
            dialog.Controls.AddRange(new Control[]{label,box,connect});dialog.AcceptButton=connect;FixedDpiLayout.Apply(dialog,0);
            if(dialog.ShowDialog(owner)!=DialogResult.OK)return;
            try{string address=ValidateAddress(box.Text); var health=ReadJson(address,"/health",null);
                if(Convert.ToString(health["auth"])!="tailscale" || new Version(Convert.ToString(health["version"]))<new Version("0.8.3"))throw new Exception("원격 PC도 0.8.3 이상으로 업데이트해야 합니다.");
                if(!health.ContainsKey("cursorlessVideo")||!Convert.ToBoolean(health["cursorlessVideo"]))throw new Exception("원격 PC 서버를 0.8.3 이상으로 업데이트하세요.");
                if(Convert.ToString(health["video"])!="h264")throw new Exception("원격 PC의 H.264 인코더를 사용할 수 없습니다.");
                foreach(var nic in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())foreach(var local in nic.GetIPProperties().UnicastAddresses)if(local.Address.ToString()==address.Split(':')[0])throw new Exception("이 PC 자신의 주소입니다. 다른 PC의 Tailscale 주소를 입력하세요.");
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
    static HttpWebRequest Request(string host,string path,string body){var r=(HttpWebRequest)WebRequest.Create("http://"+host+path);r.Proxy=null;r.AllowAutoRedirect=false;r.Timeout=4000;r.ReadWriteTimeout=8000;r.ServicePoint.ConnectionLimit=16;r.KeepAlive=true;r.ServicePoint.Expect100Continue=false;r.ServicePoint.UseNagleAlgorithm=false;
        if(body!=null){r.Method="POST";r.ContentType="application/x-www-form-urlencoded";var data=Encoding.UTF8.GetBytes(body);r.ContentLength=data.Length;using(var output=r.GetRequestStream())output.Write(data,0,data.Length);}return r;}
    static Dictionary<string,object> ReadJson(string host,string path,string body){using(var response=Request(host,path,body).GetResponse())using(var input=new StreamReader(response.GetResponseStream()))return new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(input.ReadToEnd());}
    string Fields(string action){return "client="+client+"&action="+action+"&label="+Uri.EscapeDataString(Environment.MachineName);}
    RemoteViewer(string address,Dictionary<string,object> health){
        host=address;client=LoadSetting("client","");Guid id;if(!Guid.TryParse(client,out id)){client=Guid.NewGuid().ToString("N");Save("client",client);}
        AutoScaleMode=AutoScaleMode.None;Font=new Font("Malgun Gothic",10);Text="PocketCoder · 원격 PC · Ctrl+Alt+Shift+Q로 조작 해제";BackColor=Color.Black;ClientSize=new Size(1280,720);MinimumSize=new Size(640,360);
        canvas.Dock=DockStyle.Fill;canvas.BackColor=Color.Black;Controls.Add(canvas);
        bar.Visible=false;bar.BackColor=Color.FromArgb(235,240,245);bar.Height=FixedDpiLayout.Px(54);bar.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;bar.Width=ClientSize.Width;bar.WrapContents=false;bar.AutoScroll=true;Controls.Add(bar);bar.BringToFront();
        menuButton.Text="메뉴";menuButton.BackColor=Color.FromArgb(255,205,92);menuButton.ForeColor=Color.Black;menuButton.UseVisualStyleBackColor=false;menuButton.AutoSize=true;menuButton.Size=new Size(FixedDpiLayout.Px(76),FixedDpiLayout.Px(36));menuButton.Location=new Point(ClientSize.Width-menuButton.Width-FixedDpiLayout.Px(10),FixedDpiLayout.Px(6));menuButton.Anchor=AnchorStyles.Top|AnchorStyles.Right;
        menuButton.Click+=delegate{bar.Visible=!bar.Visible;menuButton.BringToFront();};Controls.Add(menuButton);menuButton.BringToFront();
        state.AutoSize=true;state.Padding=new Padding(FixedDpiLayout.Px(4),FixedDpiLayout.Px(10),FixedDpiLayout.Px(4),0);bar.Controls.Add(state);
        claim.Text="조작 시작 / 가져오기";claim.AutoSize=true;claim.MinimumSize=new Size(FixedDpiLayout.Px(145),FixedDpiLayout.Px(34));claim.Click+=async delegate{armed=true;await Control("claim");};bar.Controls.Add(claim);
        int count=Convert.ToInt32(health["displays"]);monitorCount=count;for(int i=0;i<count;i++){int index=i;Add("모니터 "+(i+1),async delegate{await SwitchDisplay(index);});}
        Add("창 / 전체",delegate{full=!full;ApplyWindow();});Add("소리 켬 / 끔",delegate{sound=!sound;StopProcess(ref audio);if(sound)Task.Run((Action)AudioLoop);});Add("연결 종료",delegate{Close();});Add("단축키 설정",delegate{ShortcutSettings();});
        displayMode.Text=hardware?"표시: 기본":"표시: 호환";displayMode.AutoSize=true;displayMode.Height=FixedDpiLayout.Px(34);displayMode.Padding=new Padding(FixedDpiLayout.Px(6));
        displayMode.Click+=delegate{hardware=!hardware;Save("compatVideo",hardware?"false":"true");displayMode.Text=hardware?"표시: 기본":"표시: 호환";RestartVideo();};bar.Controls.Add(displayMode);
        display=Convert.ToInt32(health["display"]);revision=Convert.ToInt64(health["displayRevision"]);
        canvas.MouseMove+=delegate(object o,MouseEventArgs e){if(mouseHook==IntPtr.Zero)CanvasMove(o,e);};canvas.MouseDown+=delegate(object o,MouseEventArgs e){if(mouseHook==IntPtr.Zero)CanvasDown(o,e);};canvas.MouseUp+=delegate(object o,MouseEventArgs e){if(mouseHook==IntPtr.Zero)CanvasUp(o,e);};
        MouseMove+=delegate(object o,MouseEventArgs e){if(mouseHook==IntPtr.Zero)CanvasMove(o,e);};MouseDown+=delegate(object o,MouseEventArgs e){if(mouseHook==IntPtr.Zero)CanvasDown(o,e);};MouseUp+=delegate(object o,MouseEventArgs e){if(mouseHook==IntPtr.Zero)CanvasUp(o,e);};
        canvas.MouseWheel+=CanvasWheel;MouseWheel+=CanvasWheel;
        canvas.MouseCaptureChanged+=delegate{if(!canvas.Capture&&buttons.Count>0)ReleaseHeld();};
        keyboardProc=Keyboard;mouseProc=ObserveMouse;Shown+=async delegate{ApplyWindow();await Control("claim");joined=true;Task.Run((Action)InputLoop);Task.Run((Action)VideoLoop);Task.Run((Action)AudioLoop);timer.Start();};
        resizeTimer.Interval=250;resizeTimer.Tick+=delegate{resizeTimer.Stop();if(joined&&!stopped)RestartVideo();};
        timer.Interval=1000;timer.Tick+=async delegate{if(++diagnosticTick%5==0)ViewerLog("mouse observed="+mouseObserved+" forwarded="+mouseForwarded+" active="+ActiveInput+" armed="+armed);await Poll();};
        Deactivate+=delegate{BeginInvoke((Action)delegate{if(!ViewerForeground)SuspendInput();});};Activated+=delegate{if(mine&&armed)SetControl(true);};
        FormClosing+=delegate{stopped=true;mine=false;timer.Stop();resizeTimer.Stop();Unhook();ReleaseHeld();wake.Set();lock(requestLock){foreach(var r in streams)r.Abort();}StopProcess(ref player);StopProcess(ref audio);try{ReadJson(host,"/control",Fields("release"));}catch{}};
    }
    void Add(string text,EventHandler click){var b=new Button{Text=text,Height=FixedDpiLayout.Px(34),AutoSize=true,Padding=new Padding(FixedDpiLayout.Px(6))};b.Click+=click;bar.Controls.Add(b);}
    void ApplyWindow(){FormBorderStyle=full?FormBorderStyle.None:FormBorderStyle.Sizable;WindowState=full?FormWindowState.Maximized:FormWindowState.Normal;}
    async Task Control(string action){int epoch=++controlEpoch;try{var s=await Task.Run(()=>ReadJson(host,"/control",Fields(action)));if(epoch==controlEpoch)SetControl(Convert.ToBoolean(s["mine"]));}catch(Exception e){state.Text=e.Message;SuspendInput();}}
    static void ViewerLog(string message){try{string folder=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"PocketCoder","logs");Directory.CreateDirectory(folder);File.AppendAllText(Path.Combine(folder,"viewer.log"),DateTime.UtcNow.ToString("o")+" "+message+Environment.NewLine);}catch{}}
    void SetControl(bool value){
        if(mine!=value)ViewerLog(value?"control gained":"control lost");
        if(mine&&!value)SuspendInput();mine=value;
        if(!mine||!armed){state.Text="보기 전용 · 조작 시작을 누르세요";return;}
        if(!ViewerForeground){state.Text="조작권 있음 · 이 창을 클릭하세요";return;}
        InstallHooks();state.Text=keyboardHook!=IntPtr.Zero?"조작 중 · Ctrl+Alt+Shift+Q 탈출":"입력 준비 실패 · 로그 폴더를 확인하세요";
    }

    async Task Poll(){if(polling||stopped||!joined)return;polling=true;int epoch=controlEpoch,pollVideoGeneration=generation;try{var s=await Task.Run(()=>ReadJson(host,"/status?client="+client,null));if(epoch==controlEpoch)SetControl(Convert.ToBoolean(((Dictionary<string,object>)s["control"])["mine"]));
        if(switching||pollVideoGeneration!=generation)return;long next=Convert.ToInt64(s["displayRevision"]);display=Convert.ToInt32(s["display"]);if(next!=revision){revision=next;RestartVideo();}
    }catch{SetControl(false);state.Text="연결 확인 중…";}finally{polling=false;}}
    void RestartVideo(){Interlocked.Increment(ref generation);var request=videoRequest;if(request!=null)request.Abort();StopProcess(ref player);videoWindow=IntPtr.Zero;}
    async Task SwitchDisplay(int index){
        if(switching)return;switching=true;
        try{if(!mine){armed=true;await Control("claim");}if(!mine){state.Text="조작권을 가져오지 못했습니다.";return;}
            ReleaseHeld();var result=await Task.Run(()=>ReadJson(host,"/display","client="+client+"&index="+index));
            display=Convert.ToInt32(result["display"]);revision=Convert.ToInt64(result["displayRevision"]);RestartVideo();
        }catch(Exception e){state.Text="모니터 전환 실패: "+e.Message;}finally{switching=false;}
    }
    void Send(string body){if(stopped)return;if(Interlocked.Increment(ref queueSize)>512){Interlocked.Decrement(ref queueSize);BeginInvoke((Action)delegate{SuspendInput();Task.Run(()=>{try{ReadJson(host,"/control",Fields("release"));}catch{}});});return;}events.Enqueue("client="+client+"&screen="+display+"&"+body);wake.Set();}
    static HttpWebRequest InputRequest(string address){var r=Request(address,"/input-stream",null);r.Method="POST";r.SendChunked=true;r.AllowWriteStreamBuffering=false;r.ContentType="application/x-www-form-urlencoded";r.ReadWriteTimeout=5000;r.Timeout=System.Threading.Timeout.Infinite;return r;}
    static Stream OpenInput(HttpWebRequest request){
        // The lifetime of a chunked request is the lifetime of the viewer, not the connect timeout.
        var pending=Task<Stream>.Factory.FromAsync(request.BeginGetRequestStream,request.EndGetRequestStream,null);
        if(!pending.Wait(5000)){request.Abort();throw new TimeoutException("Input connection timeout");}return pending.Result;
    }
    static string Line(Stream input){var b=new StringBuilder();int c;while((c=input.ReadByte())!=-1){if(c==10)return b.ToString().TrimEnd('\r');if(b.Length>32768)throw new IOException("Header too large");b.Append((char)c);}throw new EndOfStreamException();}
    static void InputTransportSelfTest(){
        var firstInput=new ManualResetEventSlim(false);var listener=new System.Net.Sockets.TcpListener(IPAddress.Loopback,0);listener.Start();
        try{string address="127.0.0.1:"+((IPEndPoint)listener.LocalEndpoint).Port;
            var peer=Task.Run(()=>{using(var c=listener.AcceptTcpClient())using(var input=c.GetStream()){
                input.ReadTimeout=10000;string first=Line(input);if(!first.StartsWith("POST /input-stream "))throw new Exception("Input path");while(Line(input)!=""){}
                var body=new MemoryStream();while(true){int n=Convert.ToInt32(Line(input).Split(';')[0],16);if(n==0){Line(input);break;}var bytes=Bytes(input,n);body.Write(bytes,0,n);firstInput.Set();if(Line(input)!="")throw new Exception("Chunk framing");}
                var reply=Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}");input.Write(reply,0,reply.Length);
                return Encoding.UTF8.GetString(body.ToArray());
            }});
            var request=InputRequest(address);using(var output=OpenInput(request)){for(int i=0;i<100;i++){var bytes=Encoding.UTF8.GetBytes("client=test&screen=0&type=keydown&vk=255\n");output.Write(bytes,0,bytes.Length);output.Flush();if(i==0&&!firstInput.Wait(2000))throw new Exception("Input buffered until connection closes");if(i==0)Thread.Sleep(4500);}}
            using(var response=request.GetResponse()){}if(!peer.Wait(5000)||peer.Result.Split(new[]{'\n'},StringSplitOptions.RemoveEmptyEntries).Length!=100)throw new Exception("Persistent input framing");
        }finally{listener.Stop();}
    }
    void InputLoop(){
        while(!stopped){HttpWebRequest r=null;
            try{r=InputRequest(host);
                lock(requestLock){if(stopped)return;streams.Add(r);}
                using(var output=OpenInput(r)){
                    ViewerLog("input transport connected");
                    BeginInvoke((Action)(async delegate{if(armed&&!stopped&&ViewerForeground)await Control("claim");}));
                    while(!stopped){string item;
                        if(!events.TryDequeue(out item)){item=Interlocked.Exchange(ref position,null);if(item==null){wake.WaitOne(4);continue;}}
                        else Interlocked.Decrement(ref queueSize);
                        var bytes=Encoding.UTF8.GetBytes(item+"\n");output.Write(bytes,0,bytes.Length);output.Flush();
                    }
                }
            }catch(Exception e){ViewerLog("input transport failed: "+e.GetType().Name+" "+e.Message);if(!stopped&&IsHandleCreated)BeginInvoke((Action)delegate{SuspendInput();mine=false;state.Text="입력 연결 재시도 중…";Task.Run(()=>{try{ReadJson(host,"/control",Fields("release"));}catch{}});});}
            finally{if(r!=null)Forget(r);}if(!stopped)Thread.Sleep(300);
        }
    }
    void ReleaseHeld(){lock(heldLock){foreach(var key in keys)Send("type=keyup&vk="+key);keys.Clear();foreach(var button in buttons)Send("type=up&button="+ButtonName(button));buttons.Clear();}canvas.Capture=false;Interlocked.Exchange(ref position,null);}
    void SuspendInput(){Unhook();ReleaseHeld();}
    static bool EscapeChord(int vk,HashSet<int> held){return vk==81&&(held.Contains(162)||held.Contains(163)||held.Contains(17))&&(held.Contains(164)||held.Contains(165)||held.Contains(18))&&(held.Contains(160)||held.Contains(161)||held.Contains(16));}
    static bool Held(HashSet<int> held,int common,int left,int right){return held.Contains(common)||held.Contains(left)||held.Contains(right);}
    internal static int ShortcutMonitor(int vk,HashSet<int> held,string combo,int count){
        bool ctrl=Held(held,17,162,163),alt=Held(held,18,164,165),shift=Held(held,16,160,161);
        bool matches=combo=="Ctrl+Shift"?ctrl&&shift&&!alt:combo=="Alt+Shift"?alt&&shift&&!ctrl:ctrl&&alt&&!shift;
        return matches&&vk>=49&&vk<=57&&vk-49<count?vk-49:-1;
    }
    void ShortcutSettings(){
        using(var dialog=new Form()){dialog.Text="모니터 전환 단축키";dialog.Font=Font;dialog.AutoScaleMode=AutoScaleMode.None;dialog.ClientSize=new Size(440,180);dialog.StartPosition=FormStartPosition.CenterParent;
            var label=new Label{Text="선택한 조합 + 숫자 1~9로 모니터 전환",Left=15,Top=15,Width=410,Height=28};
            var combos=new ComboBox{Left=15,Top=55,Width=410,DropDownStyle=ComboBoxStyle.DropDownList};combos.Items.AddRange(new object[]{"Ctrl+Alt","Ctrl+Shift","Alt+Shift"});combos.SelectedItem=monitorHotkey;
            var save=new Button{Text="저장",Left=315,Top=115,Width=110,Height=42,DialogResult=DialogResult.OK};dialog.Controls.AddRange(new Control[]{label,combos,save});FixedDpiLayout.Apply(dialog,0);
            if(dialog.ShowDialog(this)==DialogResult.OK){monitorHotkey=Convert.ToString(combos.SelectedItem);Save("monitorHotkey",monitorHotkey);}
        }
    }
    static string ButtonName(int value){return value==1?"left":value==2?"right":"middle";}
    bool ViewerForeground {get{var foreground=GetForegroundWindow();return foreground!=IntPtr.Zero&&(foreground==Handle||foreground==videoWindow||IsChild(Handle,foreground)||GetAncestor(foreground,2)==Handle);}}
    bool ActiveInput {get{return mine&&armed&&!stopped&&(ViewerForeground||ContainsFocus);}}
    void InstallHooks(){if(keyboardHook!=IntPtr.Zero||stopped)return;keyboardHook=SetWindowsHookEx(13,keyboardProc,GetModuleHandle(null),0);mouseHook=SetWindowsHookEx(14,mouseProc,GetModuleHandle(null),0);if(keyboardHook==IntPtr.Zero){ViewerLog("hook installation failed: "+Marshal.GetLastWin32Error());Unhook();state.Text="입력 훅을 시작하지 못했습니다.";}}
    void Unhook(){if(keyboardHook!=IntPtr.Zero)UnhookWindowsHookEx(keyboardHook);if(mouseHook!=IntPtr.Zero)UnhookWindowsHookEx(mouseHook);keyboardHook=mouseHook=IntPtr.Zero;Cursor.Current=Cursors.Default;}
    IntPtr Keyboard(int code,IntPtr message,IntPtr data){if(code>=0&&ActiveInput){int vk=Marshal.ReadInt32(data),flags=Marshal.ReadInt32(data,8);
        bool down=message.ToInt32()==256||message.ToInt32()==260;
        if(down&&localShortcutUps.Contains(vk))return new IntPtr(1);
        if(!down&&localShortcutUps.Remove(vk))return new IntPtr(1);
        int monitor=ShortcutMonitor(vk,keys,monitorHotkey,monitorCount);
        if(down&&monitor>=0){localShortcutUps.Add(vk);BeginInvoke((Action)(async delegate{ReleaseHeld();await SwitchDisplay(monitor);}));return new IntPtr(1);}
        lock(heldLock){if(down&&EscapeChord(vk,keys)){BeginInvoke((Action)delegate{armed=false;SuspendInput();mine=false;Task.Run(()=>{try{ReadJson(host,"/control",Fields("release"));}catch{}});state.Text="보기 전용 · 로컬 복귀";bar.Visible=true;menuButton.BringToFront();});return new IntPtr(1);}if(down)keys.Add(vk);else keys.Remove(vk);}
        Send("type="+(down?"keydown":"keyup")+"&vk="+vk+"&extended="+(((flags&1)!=0)?"1":"0"));return new IntPtr(1);
    }return CallNextHookEx(IntPtr.Zero,code,message,data);}
    IntPtr ObserveMouse(int code,IntPtr message,IntPtr data){
        if(code>=0){mouseObserved++;if(ActiveInput){
            var packet=(MouseData)Marshal.PtrToStructure(data,typeof(MouseData));Point point=canvas.PointToClient(packet.point);int x,y,msg=message.ToInt32();
            if(PointerPosition(point,out x,out y)){
                mouseForwarded++;
                if(msg==512){CanvasMove(canvas,new MouseEventArgs(MouseButtons.None,0,point.X,point.Y,0));return CallNextHookEx(IntPtr.Zero,code,message,data);}
                MouseButtons button=msg==513||msg==514?MouseButtons.Left:msg==516||msg==517?MouseButtons.Right:msg==519||msg==520?MouseButtons.Middle:MouseButtons.None;
                if(button!=MouseButtons.None){var e=new MouseEventArgs(button,1,point.X,point.Y,0);if(msg==513||msg==516||msg==519)CanvasDown(canvas,e);else CanvasUp(canvas,e);return new IntPtr(1);}
                if(msg==522){Send("type=wheel&amount="+Math.Max(-15,Math.Min(15,-(short)(packet.mouseData>>16)/120)));return new IntPtr(1);}
            }else if(buttons.Count>0)ReleaseHeld();
        }}return CallNextHookEx(IntPtr.Zero,code,message,data);
    }
    bool PointerPosition(Point point,out int x,out int y){
        if(menuButton.RectangleToScreen(menuButton.ClientRectangle).Contains(canvas.PointToScreen(point))||(bar.Visible&&bar.RectangleToScreen(bar.ClientRectangle).Contains(canvas.PointToScreen(point)))){x=y=0;return false;}
        return MapPoint(point,canvas.ClientSize,width,height,out x,out y);
    }
    void CanvasMove(object sender,MouseEventArgs e){
        if(!ActiveInput)return;int x,y;if(!PointerPosition(e.Location,out x,out y))return;
        long now=Stopwatch.GetTimestamp();if((now-lastMove)*1000/Stopwatch.Frequency<4)return;lastMove=now;
        Interlocked.Exchange(ref position,"client="+client+"&screen="+display+"&type=pos&x="+x+"&y="+y);wake.Set();
    }
    static int PointerButton(MouseButtons button){return button==MouseButtons.Left?1:button==MouseButtons.Right?2:button==MouseButtons.Middle?3:0;}
    void CanvasDown(object sender,MouseEventArgs e){
        if(!ActiveInput)return;int x,y,button=PointerButton(e.Button);if(button==0||!PointerPosition(e.Location,out x,out y))return;
        Interlocked.Exchange(ref position,null);Send("type=pos&x="+x+"&y="+y);
        lock(heldLock)buttons.Add(button);canvas.Capture=true;Send("type=down&button="+ButtonName(button));
    }
    void CanvasUp(object sender,MouseEventArgs e){
        int button=PointerButton(e.Button);bool held;lock(heldLock)held=buttons.Remove(button);
        if(held)Send("type=up&button="+ButtonName(button));if(buttons.Count==0)canvas.Capture=false;
    }
    void CanvasWheel(object sender,MouseEventArgs e){
        var handled=e as HandledMouseEventArgs;if(handled!=null&&handled.Handled)return;
        int x,y;if(!ActiveInput||!PointerPosition(canvas.PointToClient(Cursor.Position),out x,out y))return;
        Send("type=wheel&amount="+Math.Max(-15,Math.Min(15,-e.Delta/120)));if(handled!=null)handled.Handled=true;
    }
    internal static bool MapPoint(Point p,Size area,int w,int h,out int x,out int y){double scale=Math.Min((double)area.Width/w,(double)area.Height/h);double left=(area.Width-w*scale)/2,top=(area.Height-h*scale)/2;x=y=0;if(scale<=0||p.X<left||p.Y<top||p.X>=left+w*scale||p.Y>=top+h*scale)return false;x=(int)Math.Round((p.X-left)*10000/Math.Max(1,w*scale-1));y=(int)Math.Round((p.Y-top)*10000/Math.Max(1,h*scale-1));x=Math.Max(0,Math.Min(10000,x));y=Math.Max(0,Math.Min(10000,y));return true;}
    static int Int(Stream s){var b=Bytes(s,4);return (b[0]<<24)|(b[1]<<16)|(b[2]<<8)|b[3];}
    static byte[] Bytes(Stream s,int count){var b=new byte[count];int n=0;while(n<count){int got=s.Read(b,n,count-n);if(got==0)throw new EndOfStreamException();n+=got;}return b;}
    HttpWebRequest StreamRequest(string path){var r=Request(host,path,null);lock(requestLock){if(stopped)throw new OperationCanceledException();streams.Add(r);}return r;}
    void Forget(HttpWebRequest r){lock(requestLock)streams.Remove(r);r.Abort();}
    Process Play(string args){var path=Path.Combine(root,"bin","ffplay.exe");if(!File.Exists(path))throw new IOException("설치본의 bin\\ffplay.exe가 없습니다. 다시 설치하세요.");var p=new Process();p.StartInfo=new ProcessStartInfo(path,args){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true};
        // Match the WinForms host's system-DPI awareness before embedding the SDL child window.
        p.StartInfo.EnvironmentVariables["SDL_WINDOWS_DPI_AWARENESS"]="system";
        p.StartInfo.EnvironmentVariables["SDL_WINDOWS_DPI_SCALING"]="0";
        p.Start();return p;}
    static void StopProcess(ref Process slot){var p=Interlocked.Exchange(ref slot,null);if(p!=null){try{if(!p.HasExited)p.Kill();}catch{}try{p.Dispose();}catch{}}}
    void VideoLoop(){while(!stopped){HttpWebRequest r=null;Process p=null;int gen=generation;try{r=StreamRequest("/video?fps=60&quality=native&mbps=60&cursor=0&live=1");videoRequest=r;using(var response=r.GetResponse())using(var input=response.GetResponseStream()){
        if(Int(input)!=0x50435631)throw new IOException("잘못된 영상 헤더");width=Int(input);height=Int(input);int fps=Int(input);if(width<1||width>16384||height<1||height>16384||fps<1||fps>240)throw new IOException("잘못된 영상 크기");
        Size target=Size.Empty;Invoke((Action)delegate{target=canvas.ClientSize;});p=Play((hardware?"-hwaccel d3d11va ":"")+"-loglevel error -noborder -x "+target.Width+" -y "+target.Height+" -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -sync ext -f h264 -i pipe:0");player=p;
        Process embedded=p;Task.Run(()=>{for(int i=0;i<100&&!stopped&&!embedded.HasExited;i++){embedded.Refresh();if(embedded.MainWindowHandle!=IntPtr.Zero){BeginInvoke((Action)delegate{if(stopped||embedded.HasExited||player!=embedded)return;videoWindow=embedded.MainWindowHandle;SetParent(videoWindow,canvas.Handle);SetWindowLong(videoWindow,-16,0x50000000);EnableWindow(videoWindow,false);MoveWindow(embedded.MainWindowHandle,0,0,canvas.Width,canvas.Height,true);SetWindowPos(embedded.MainWindowHandle,IntPtr.Zero,0,0,0,0,0x0013);Activate();canvas.Focus();});break;}Thread.Sleep(50);}});
        while(!stopped&&gen==generation){int size=Int(input);Bytes(input,8);if(size<1||size>8388608)throw new IOException("영상 패킷 크기 오류");var b=Bytes(input,size);p.StandardInput.BaseStream.Write(b,0,b.Length);p.StandardInput.BaseStream.Flush();}
    }}catch(Exception e){if(!stopped&&IsHandleCreated)BeginInvoke((Action)delegate{state.Text="영상 재연결: "+e.Message;});}finally{if(r!=null)Forget(r);if(p!=null){try{if(p.HasExited&&p.ExitCode!=0){hardware=false;if(IsHandleCreated&&!stopped)BeginInvoke((Action)delegate{displayMode.Text="표시: 호환";});}if(!p.HasExited)p.Kill();p.Dispose();}catch{}}if(player==p)player=null;if(videoRequest==r)videoRequest=null;}if(!stopped&&gen==generation)Thread.Sleep(300);}}
    void AudioLoop(){HttpWebRequest r=null;Process p=null;try{r=StreamRequest("/audio");using(var response=r.GetResponse())using(var input=response.GetResponseStream()){
        if(Int(input)!=0x50434131)throw new IOException("잘못된 오디오 헤더");int rate=Int(input),channels=Int(input),bits=Int(input);if(rate<8000||rate>192000||channels!=2||bits!=16)throw new IOException("지원하지 않는 오디오 형식");
        p=Play("-loglevel error -nodisp -autoexit -fflags nobuffer -f s16le -ar "+rate+" -ac "+channels+" -i pipe:0");audio=p;
        while(!stopped&&sound){int size=Int(input);if(size<4||size>262144||size%4!=0)throw new IOException("오디오 패킷 오류");var b=Bytes(input,size);p.StandardInput.BaseStream.Write(b,0,size);p.StandardInput.BaseStream.Flush();}
    }}catch{}finally{if(r!=null)Forget(r);if(p!=null){try{if(!p.HasExited)p.Kill();p.Dispose();}catch{}}if(audio==p)audio=null;}}
    protected override void OnResize(EventArgs e){base.OnResize(e);if(resizeTimer!=null&&joined&&!stopped){resizeTimer.Stop();resizeTimer.Start();}try{if(videoWindow!=IntPtr.Zero)MoveWindow(videoWindow,0,0,canvas.Width,canvas.Height,true);}catch{}}
    internal static void SelfTest(){if(ShortcutMonitor(50,new HashSet<int>{162,164},"Ctrl+Alt",4)!=1||ShortcutMonitor(50,new HashSet<int>{162},"Ctrl+Alt",4)!=-1||ShortcutMonitor(53,new HashSet<int>{162,164},"Ctrl+Alt",4)!=-1||ShortcutMonitor(49,new HashSet<int>{162,160},"Ctrl+Shift",2)!=0)throw new Exception("Monitor hotkey routing");InputTransportSelfTest();if(!EscapeChord(81,new HashSet<int>{162,164,160})||EscapeChord(81,new HashSet<int>{162,164})||EscapeChord(65,new HashSet<int>{162,164,160}))throw new Exception("Escape chord");if(ValidateAddress("100.64.0.1")!="100.64.0.1:8765")throw new Exception("Address");bool rejected=false;try{ValidateAddress("8.8.8.8");}catch{rejected=true;}if(!rejected)throw new Exception("Public address accepted");int x,y;if(MapPoint(new Point(10,10),new Size(1000,1000),1920,1080,out x,out y))throw new Exception("Letterbox");if(!MapPoint(new Point(500,500),new Size(1000,1000),1920,1080,out x,out y)||Math.Abs(x-5000)>20||Math.Abs(y-5000)>20)throw new Exception("Coordinates");using(var m=new MemoryStream(new byte[]{0x50,0x43,0x56,0x31})){if(Int(m)!=0x50435631)throw new Exception("Endian");}Console.WriteLine("PASS: viewer address / letterbox coordinates / wire header");}
    delegate IntPtr Hook(int code,IntPtr message,IntPtr data);
    [StructLayout(LayoutKind.Sequential)]struct MouseData{public Point point;public uint mouseData,flags,time;public UIntPtr extra;}
    [DllImport("user32.dll",SetLastError=true)]static extern IntPtr SetWindowsHookEx(int id,Hook callback,IntPtr module,uint thread);
    [DllImport("user32.dll")]static extern bool UnhookWindowsHookEx(IntPtr hook);
    [DllImport("user32.dll")]static extern IntPtr CallNextHookEx(IntPtr hook,int code,IntPtr message,IntPtr data);
    [DllImport("user32.dll")]static extern IntPtr SetCursor(IntPtr cursor);
    [DllImport("user32.dll")]static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")]static extern bool IsChild(IntPtr parent,IntPtr child);
    [DllImport("user32.dll")]static extern IntPtr GetAncestor(IntPtr window,uint flags);
    [DllImport("kernel32.dll",CharSet=CharSet.Auto)]static extern IntPtr GetModuleHandle(string name);
    [DllImport("user32.dll")]static extern bool EnableWindow(IntPtr window,bool enabled);
    [DllImport("user32.dll")]static extern IntPtr SetParent(IntPtr child,IntPtr parent);
    [DllImport("user32.dll")]static extern int SetWindowLong(IntPtr handle,int index,int value);
    [DllImport("user32.dll")]static extern bool MoveWindow(IntPtr handle,int x,int y,int w,int h,bool repaint);
    [DllImport("user32.dll")]static extern bool SetWindowPos(IntPtr h,IntPtr after,int x,int y,int w,int height,uint flags);
}

// WinForms point fonts already follow the system DPI. Scale the fixed 96-DPI rectangles
// once as well; leaving 96-DPI button widths beside enlarged fonts clips Korean captions.
internal static class FixedDpiLayout {
    internal static float Factor {get{using(var g=Graphics.FromHwnd(IntPtr.Zero))return Math.Max(1f,g.DpiX/96f);}}
    internal static int Px(int value){return (int)Math.Round(value*Factor);}
    internal static void Apply(Form form,float preview){
        float actual=Factor,scale=preview>0?preview:actual;Size baseSize=form.ClientSize;
        var controls=new List<Control>();var bounds=new List<Rectangle>();var fonts=new List<Font>();
        foreach(Control c in form.Controls){controls.Add(c);bounds.Add(c.Bounds);fonts.Add(c.Font);}
        form.SuspendLayout();form.AutoScaleMode=AutoScaleMode.None;
        for(int i=0;i<controls.Count;i++){var c=controls[i];var b=bounds[i];
            if(preview>0)c.Font=new Font(fonts[i].FontFamily,fonts[i].Size*scale/actual,fonts[i].Style);
            c.Bounds=new Rectangle((int)Math.Round(b.X*scale),(int)Math.Round(b.Y*scale),(int)Math.Round(b.Width*scale),(int)Math.Round(b.Height*scale));
        }
        Size desired=new Size((int)Math.Round(baseSize.Width*scale),(int)Math.Round(baseSize.Height*scale));
        var work=Screen.FromControl(form).WorkingArea;
        form.AutoScroll=true;form.AutoScrollMinSize=desired;
        form.ClientSize=new Size(Math.Min(desired.Width,Math.Max(360,work.Width-40)),Math.Min(desired.Height,Math.Max(240,work.Height-100)));
        form.ResumeLayout(true);
    }
}

internal sealed class InputSurface : Panel {
    internal InputSurface(){SetStyle(ControlStyles.Selectable,true);TabStop=true;}
}
