using System;
using System.IO;
using System.Diagnostics;
using System.Drawing;
using System.Net;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using System.Web.Script.Serialization;
using System.Collections.Generic;
using System.Security.Cryptography;

class PocketCoderPC : Form {
    readonly string root=AppDomain.CurrentDomain.BaseDirectory;
    readonly Label status=new Label(),address=new Label(),dependencies=new Label(),updateLabel=new Label();
    readonly Button start=new Button(),stop=new Button(),copy=new Button(),logs=new Button();
    readonly PictureBox qr=new PictureBox();
    readonly System.Windows.Forms.Timer timer=new System.Windows.Forms.Timer();
    readonly JavaScriptSerializer json=new JavaScriptSerializer();
    readonly NotifyIcon tray=new NotifyIcon();
    string ip,version="0.7.0"; bool busy,checking,ready,owned,exitRequested,qrLoaded;
    static string Hash(string value) { using(var sha=SHA256.Create())return BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(value))).Replace("-","").ToLowerInvariant().Substring(0,16); }
    string Instance { get { return Hash(Path.GetFullPath(Path.Combine(root,"pocketcoder-server.jar")).ToLowerInvariant()); } }
    PocketCoderPC() {
        Text="PocketCoder PC"; ClientSize=new Size(760,440); StartPosition=FormStartPosition.CenterScreen;
        FormBorderStyle=FormBorderStyle.FixedDialog; MaximizeBox=false; Font=new Font("Malgun Gothic",10);
        var title=AddLabel("PocketCoder 원격 연결",20,18,710,34); title.Font=new Font(Font.FontFamily,16,FontStyle.Bold);
        SetupLabel(status,"상태 확인 중…",20,67,440,35); SetupLabel(address,"Tailscale 주소 확인 중…",20,108,440,28);
        SetupLabel(dependencies,"준비 상태 확인 중…",20,145,440,48);
        AddLabel("PC와 휴대폰의 Tailscale에 같은 계정으로 로그인하세요.\n화면과 PC 재생음을 전송합니다. 마이크는 수음하지 않습니다.",20,201,440,44);
        SetupButton(start,"시작",20,260,100); SetupButton(stop,"종료",130,260,100); SetupButton(copy,"주소 복사",240,260,100); SetupButton(logs,"로그 폴더",350,260,100);
        start.Enabled=false; stop.Enabled=false; copy.Enabled=false;
        start.Click+=async delegate { await Act("Start"); }; stop.Click+=async delegate { await Act("Stop"); };
        copy.Click+=delegate { if(ip!=null)Clipboard.SetText(ip+":8765"); };
        logs.Click+=delegate { var path=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"PocketCoder","logs"); Directory.CreateDirectory(path);Process.Start("explorer.exe","\""+path+"\""); };
        qr.Location=new Point(480,74);qr.Size=new Size(256,256);qr.SizeMode=PictureBoxSizeMode.Zoom;qr.BackColor=Color.White;Controls.Add(qr);
        AddLabel("서버 시작 후 QR 표시 · 휴대폰에서 스캔",480,341,270,38);
        var update=new Button();SetupButton(update,"업데이트 확인",20,316,180);update.Click+=async delegate {await CheckUpdates(true);};
        var reload=new Button();SetupButton(reload,"주소 새로고침",216,316,180);reload.Click+=async delegate {qrLoaded=false;await Initialize();};
        var tail=new Button();SetupButton(tail,"Tailscale 설치 / 열기",20,370,180);tail.Click+=delegate {
            string exe=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"Tailscale","tailscale-ipn.exe");
            if(File.Exists(exe))Process.Start(exe);else Process.Start("https://tailscale.com/download/windows");
        };
        var hide=new Button();SetupButton(hide,"트레이로 숨기기",216,370,180);hide.Click+=delegate {Hide();};
        SetupLabel(updateLabel,"",20,416,710,22);updateLabel.Font=new Font(Font.FontFamily,9);
        var menu=new ContextMenuStrip();menu.Items.Add("열기",null,delegate{ShowWindow();});
        menu.Items.Add("서버 시작",null,async delegate{await Act("Start");});menu.Items.Add("서버 종료",null,async delegate{await Act("Stop");});
        menu.Items.Add("업데이트 확인",null,async delegate{await CheckUpdates(true);});menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("프로그램만 끝내기 · 서버 유지",null,delegate{exitRequested=true;Close();});
        menu.Items.Add("서버 종료 후 끝내기",null,async delegate{await Act("Stop");exitRequested=true;Close();});
        tray.Icon=SystemIcons.Application;tray.Text="PocketCoder PC";tray.ContextMenuStrip=menu;tray.Visible=true;tray.DoubleClick+=delegate{ShowWindow();};
        Resize+=delegate{if(WindowState==FormWindowState.Minimized)Hide();};
        FormClosing+=delegate(object sender,FormClosingEventArgs e){if(!exitRequested&&e.CloseReason==CloseReason.UserClosing){e.Cancel=true;Hide();}};
        timer.Interval=3000;timer.Tick+=async delegate{await RefreshStatus();};
        Shown+=async delegate{await Initialize();timer.Start();await CheckUpdates(false);};
        FormClosed+=delegate{timer.Stop();timer.Dispose();tray.Visible=false;tray.Dispose();if(qr.Image!=null)qr.Image.Dispose();};
    }
    void ShowWindow(){Show();WindowState=FormWindowState.Normal;Activate();}
    Label AddLabel(string text,int x,int y,int w,int h){var l=new Label();SetupLabel(l,text,x,y,w,h);return l;}
    void SetupLabel(Label l,string text,int x,int y,int w,int h){l.Text=text;l.Location=new Point(x,y);l.Size=new Size(w,h);Controls.Add(l);}
    void SetupButton(Button b,string text,int x,int y,int w){b.Text=text;b.Location=new Point(x,y);b.Size=new Size(w,42);Controls.Add(b);}
    Dictionary<string,object> Run(string args){
        var info=new ProcessStartInfo("powershell.exe","-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \""+Path.Combine(root,"pocketcoder-pc.ps1")+"\" "+args);
        info.UseShellExecute=false;info.CreateNoWindow=true;info.RedirectStandardOutput=true;info.RedirectStandardError=true;info.StandardOutputEncoding=Encoding.UTF8;
        using(var p=Process.Start(info)){var output=p.StandardOutput.ReadToEndAsync();var error=p.StandardError.ReadToEndAsync();if(!p.WaitForExit(45000)){p.Kill();throw new Exception("응답 시간이 초과됐습니다. 로그 폴더를 확인하세요.");}string text=output.Result.Trim();if(text.Length==0)throw new Exception("설치 파일을 다시 실행하거나 ZIP 전체를 같은 폴더에 풀어주세요.");return json.Deserialize<Dictionary<string,object>>(text);}
    }
    static byte[] Get(string url,int timeout){
        var request=(HttpWebRequest)WebRequest.Create(url);request.Proxy=null;request.Timeout=timeout;request.ReadWriteTimeout=timeout;request.UserAgent="PocketCoder-update-check";
        using(var response=request.GetResponse())using(var input=response.GetResponseStream())using(var bytes=new MemoryStream()){
            var buffer=new byte[4096];int count;while((count=input.Read(buffer,0,buffer.Length))>0){if(bytes.Length+count>1048576)throw new IOException("Response too large");bytes.Write(buffer,0,count);}return bytes.ToArray();
        }
    }
    async Task Initialize(){try{
        var data=await Task.Run(()=>Run("-CheckOnly"));ip=data.ContainsKey("Address")?Convert.ToString(data["Address"]):null;if(String.IsNullOrWhiteSpace(ip))ip=null;
        address.Text=ip==null?"Tailscale 연결 후 [주소 새로고침]을 누르세요.":"휴대폰에 입력: "+ip+":8765";
        owned=data.ContainsKey("OwnedServer")&&(bool)data["OwnedServer"];
        dependencies.Text="Java 실행 환경: "+((bool)data["Java17"]?"준비됨":"없음")+" / FFmpeg: "+((bool)data["FFmpeg"]?"준비됨":"없음")+"\n설치본에는 실행 환경과 FFmpeg가 포함됩니다.";
        ready=(bool)data["Java17"]&&(bool)data["JarPresent"]&&ip!=null;copy.Enabled=ip!=null;start.Enabled=ready;await RefreshStatus();
    }catch(Exception e){status.Text=e.Message;}}
    async Task RefreshStatus(){if(checking||busy||ip==null)return;checking=true;try{
        var data=await Task.Run(()=>json.Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(Get("http://"+ip+":8765/health",1200))));
        if(!data.ContainsKey("auth")||Convert.ToString(data["auth"])!="tailscale")throw new Exception("Tailscale 모드가 아닙니다.");
        bool same=data.ContainsKey("instance")?Convert.ToString(data["instance"])==Instance:owned;
        if(!same){status.Text="다른 폴더의 서버 실행 중 · 기존 서버를 먼저 종료하세요.";start.Enabled=false;stop.Enabled=false;return;}
        version=Convert.ToString(data["version"]);status.Text="실행 중 · 서버 "+version+" · Tailscale 전용";start.Enabled=false;stop.Enabled=true;tray.Text="PocketCoder PC · 실행 중";
        if(!qrLoaded&&data.ContainsKey("controlLease")){
            byte[] image=await Task.Run(()=>Get("http://"+ip+":8765/connect-qr",2000));
            using(var stream=new MemoryStream(image))using(var original=Image.FromStream(stream)){var old=qr.Image;qr.Image=new Bitmap(original);if(old!=null)old.Dispose();}qrLoaded=true;
        }
    }catch{status.Text="종료됨 / 연결 대기";stop.Enabled=false;start.Enabled=ready;tray.Text="PocketCoder PC · 대기";}finally{checking=false;}}
    async Task Act(string action){if(busy)return;busy=true;start.Enabled=false;stop.Enabled=false;status.Text=action=="Start"?"시작 중…":"종료 중…";try{
        var data=await Task.Run(()=>Run("-Action "+action));status.Text=Convert.ToString(data["message"]);if(!(bool)data["ok"])MessageBox.Show(this,status.Text,"PocketCoder");else owned=action=="Start";
    }catch(Exception e){MessageBox.Show(this,e.Message,"PocketCoder");}finally{busy=false;}await RefreshStatus();}
    async Task CheckUpdates(bool manual){
        var stamp=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"PocketCoder","update-check.txt");
        if(!manual&&File.Exists(stamp)&&DateTime.UtcNow-File.GetLastWriteTimeUtc(stamp)<TimeSpan.FromDays(1))return;
        try{
            var result=await Task.Run(()=>{
                var rootData=json.Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(Get("https://api.github.com/repos/gguma109/PocketCoder/actions/workflows/build-android-apk.yml/runs?branch=main&status=success&per_page=1",5000)));
                Dictionary<string,object> run=null;foreach(var item in (System.Collections.IEnumerable)rootData["workflow_runs"]){run=(Dictionary<string,object>)item;break;}if(run==null)return null;string sha=Convert.ToString(run["head_sha"]);
                if(!System.Text.RegularExpressions.Regex.IsMatch(sha,"^[a-f0-9]{40}$"))return null;
                var manifest=json.Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(Get("https://raw.githubusercontent.com/gguma109/PocketCoder/"+sha+"/updates.json",5000)));
                return new string[]{Convert.ToString(manifest["serverVersion"]),Convert.ToString(run["html_url"])};
            });
            Directory.CreateDirectory(Path.GetDirectoryName(stamp));File.WriteAllText(stamp,DateTime.UtcNow.ToString("O"));
            if(result!=null&&new Version(result[0])>new Version("0.7.0")){
                updateLabel.Text="새 버전 "+result[0]+"이 있습니다.";
                if(MessageBox.Show(this,"새 버전 "+result[0]+"을 다운로드하시겠습니까?","PocketCoder",MessageBoxButtons.YesNo)==DialogResult.Yes&&result[1].StartsWith("https://github.com/gguma109/PocketCoder/actions/runs/"))Process.Start(result[1]);
            }else if(manual)MessageBox.Show(this,"현재 최신 버전입니다.","PocketCoder");
        }catch{if(manual)MessageBox.Show(this,"업데이트 확인 실패 · 인터넷 연결을 확인하세요.","PocketCoder");}
    }
    [STAThread]static void Main(string[] args){
        string eventName="Local\\PocketCoderExit-"+Hash(AppDomain.CurrentDomain.BaseDirectory.ToLowerInvariant());
        if(args.Length==1&&args[0]=="--prepare-update") { try { using(var existing=EventWaitHandle.OpenExisting(eventName))existing.Set();Thread.Sleep(300); } catch(WaitHandleCannotBeOpenedException){} return; }
        bool created;using(var mutex=new Mutex(true,"Local\\PocketCoderPC-"+Hash(AppDomain.CurrentDomain.BaseDirectory.ToLowerInvariant()),out created)){
        if(!created){MessageBox.Show("이미 실행 중입니다. 작업 표시줄의 트레이 아이콘을 확인하세요.","PocketCoder");return;}
        Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
        using(var signal=new EventWaitHandle(false,EventResetMode.AutoReset,eventName)) {
            var form=new PocketCoderPC();
            var registration=ThreadPool.RegisterWaitForSingleObject(signal,delegate { if(!form.IsDisposed&&form.IsHandleCreated)form.BeginInvoke((Action)delegate{form.exitRequested=true;form.Close();}); },null,-1,true);
            Application.Run(form);
            registration.Unregister(null);
        }
        mutex.ReleaseMutex();
    }}
}
