using System;
using System.IO;
using System.Diagnostics;
using System.Drawing;
using System.Net;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using System.Web.Script.Serialization;
using System.Collections.Generic;

class PocketCoderPC : Form {
    readonly string root = AppDomain.CurrentDomain.BaseDirectory;
    readonly Label status = new Label(), address = new Label(), dependencies = new Label();
    readonly Button start = new Button(), stop = new Button(), copy = new Button(), logs = new Button();
    readonly Timer timer = new Timer();
    readonly JavaScriptSerializer json = new JavaScriptSerializer();
    string ip; bool busy, checking;
    PocketCoderPC() {
        Text="PocketCoder PC"; ClientSize=new Size(560,330); StartPosition=FormStartPosition.CenterScreen;
        FormBorderStyle=FormBorderStyle.FixedDialog; MaximizeBox=false; Font=new Font("Malgun Gothic",10);
        var title=LabelAt("PocketCoder 원격 연결",18,32); title.Font=new Font(Font.FontFamily,16,FontStyle.Bold);
        SetupLabel(status,"상태 확인 중…",65,28); SetupLabel(address,"Tailscale 연결 주소 확인 중…",103,28);
        SetupLabel(dependencies,"Java / FFmpeg 준비 상태 확인 중…",140,28);
        LabelAt("PC와 휴대폰의 Tailscale에 같은 계정으로 로그인하세요.",177,26);
        SetupButton(start,"시작",20); SetupButton(stop,"종료",152); SetupButton(copy,"주소 복사",284); SetupButton(logs,"로그 폴더",416);
        start.Enabled=false; stop.Enabled=false; copy.Enabled=false;
        start.Click+=async delegate { await Act("Start"); }; stop.Click+=async delegate { await Act("Stop"); };
        copy.Click+=delegate { if(ip!=null) Clipboard.SetText(ip+":8765"); };
        logs.Click+=delegate { string path=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"PocketCoder","logs"); Directory.CreateDirectory(path); Process.Start("explorer.exe","\""+path+"\""); };
        var note=LabelAt("창을 닫아도 서버는 계속 실행됩니다. 끄려면 [종료]를 누르세요.",278,38); note.Font=new Font(Font.FontFamily,9);
        timer.Interval=3000; timer.Tick+=async delegate { await RefreshStatus(); };
        Shown+=async delegate { await Initialize(); timer.Start(); };
        FormClosed+=delegate { timer.Stop(); timer.Dispose(); };
    }
    Label LabelAt(string text,int y,int h) { var l=new Label(); SetupLabel(l,text,y,h); return l; }
    void SetupLabel(Label l,string text,int y,int h) { l.Text=text; l.Location=new Point(20,y); l.Size=new Size(520,h); Controls.Add(l); }
    void SetupButton(Button b,string text,int x) { b.Text=text; b.Location=new Point(x,214); b.Size=new Size(124,43); Controls.Add(b); }
    Dictionary<string,object> Run(string args) {
        var info=new ProcessStartInfo("powershell.exe","-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \""+Path.Combine(root,"pocketcoder-pc.ps1")+"\" "+args);
        info.UseShellExecute=false; info.CreateNoWindow=true; info.RedirectStandardOutput=true; info.RedirectStandardError=true;
        info.StandardOutputEncoding=Encoding.UTF8;
        using(var p=Process.Start(info)) {
            var output=p.StandardOutput.ReadToEndAsync(); var error=p.StandardError.ReadToEndAsync();
            if(!p.WaitForExit(45000)) { p.Kill(); throw new Exception("시간이 초과됐습니다. 로그 폴더를 확인하세요."); }
            string text=output.Result.Trim();
            if(text.Length==0) throw new Exception("PC 제어 스크립트를 실행하지 못했습니다. 배포 ZIP 전체를 같은 폴더에 풀어주세요.");
            return json.Deserialize<Dictionary<string,object>>(text);
        }
    }
    async Task Initialize() {
        try {
            var data=await Task.Run(()=>Run("-CheckOnly"));
            ip=data.ContainsKey("Address")?Convert.ToString(data["Address"]):null; if(String.IsNullOrWhiteSpace(ip))ip=null;
            address.Text=ip==null?"Tailscale을 켜고 이 창을 다시 실행하세요.":"휴대폰에 입력: "+ip+":8765";
            dependencies.Text="Java 17+: "+((bool)data["Java17"]?"준비됨":"설치 필요")+"    /    FFmpeg: "+((bool)data["FFmpeg"]?"준비됨":"없음 · 저속 호환 모드");
            start.Enabled=(bool)data["Java17"] && (bool)data["JarPresent"] && ip!=null; copy.Enabled=ip!=null;
            await RefreshStatus();
        } catch(Exception e) { status.Text=e.Message; }
    }
    async Task RefreshStatus() {
        if(checking || busy || ip==null)return; checking=true;
        try {
            string version=await Task.Run(()=> {
                var request=(HttpWebRequest)WebRequest.Create("http://"+ip+":8765/health"); request.Proxy=null; request.Timeout=1200; request.ReadWriteTimeout=1200;
                using(var response=request.GetResponse())using(var reader=new StreamReader(response.GetResponseStream())) {
                    var data=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(reader.ReadToEnd());
                    if(!data.ContainsKey("auth") || Convert.ToString(data["auth"])!="tailscale")throw new Exception("인증 모드 확인 필요");
                    return Convert.ToString(data["version"]);
                }
            });
            status.Text="실행 중 · 서버 "+version+" · Tailscale 전용"; start.Enabled=false; stop.Enabled=true;
        } catch { status.Text="종료됨 / 연결 대기"; stop.Enabled=false; start.Enabled=true; }
        finally { checking=false; }
    }
    async Task Act(string action) {
        if(busy)return; busy=true; start.Enabled=false; stop.Enabled=false; status.Text=action=="Start"?"시작 중…":"종료 중…";
        try {
            var data=await Task.Run(()=>Run("-Action "+action)); status.Text=Convert.ToString(data["message"]);
            if(!(bool)data["ok"])MessageBox.Show(this,status.Text,"PocketCoder");
        } catch(Exception e) { MessageBox.Show(this,e.Message,"PocketCoder"); }
        finally { busy=false; }
        await RefreshStatus();
    }
    [STAThread] static void Main() { Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false); Application.Run(new PocketCoderPC()); }
}
