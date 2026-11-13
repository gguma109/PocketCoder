# 외부에서 PocketCoder 연결

1. PC와 Android 휴대폰에 Tailscale을 설치하고 같은 개인 계정(동일 tailnet)으로 로그인합니다.
2. 두 기기에서 Tailscale을 연결 상태로 유지합니다.
3. 기존 PocketCoder 서버 창에서 Ctrl+C를 눌러 종료합니다.
4. 이 ZIP의 파일을 모두 같은 폴더에 풀고 `run-tailscale-windows.bat`를 실행합니다.
   서버가 활성 Tailscale 인터페이스를 찾아 그 IPv4 주소에만 바인딩합니다.
5. 자동 감지가 안 되면 `run-windows.bat`에서 Tailscale 어댑터의 IPv4를 직접 입력합니다.
6. 휴대폰 PocketCoder 0.3.0에 서버 창의 `Bind` 주소만 입력하고 연결합니다. **토큰은 없습니다.**
   최초 연결 후에는 주소를 기억하여 다음 앱 실행부터 자동으로 연결합니다.
7. 휴대폰 Wi-Fi를 끄고 LTE/5G에서 연결해 외부 접속을 확인합니다. PC는 켜져 있고 절전 상태가 아니어야 합니다.

직접 실행: `java -jar pocketcoder-server.jar --bind=tailscale --port=8765 --fps=15`

로그인은 Tailscale 앱에서 수행합니다. 서버는 로컬 Tailscale CLI의 whois 결과로 접속 기기가
PC와 같은 Tailscale 사용자 소유인지 확인합니다. 같은 tailnet의 다른 사용자나 태그 기기는 거부합니다.
Tailscale 로그아웃/기기 로그인 만료/CLI 조회 실패 시 접속을 허용하지 않습니다.
LAN/이전 서버 호환은 앱의 '고급: LAN / 이전 서버 연결'에서만 토큰을 사용합니다.

Tailscale의 터널이 암호화를 제공합니다. 서버 자체는 HTTP이고 인터넷에 직접 노출하면 안 됩니다.
공유기 포트포워딩, Tailscale Funnel, 공용 터널은 사용하지 않습니다.
Tailscale ACL/접근 정책에서 본인 휴대폰이 PC의 TCP 8765에 접근할 수 있어야 합니다.
Windows 방화벽이 막으면 로컬 주소를 PC Tailscale IPv4, 원격 주소를 휴대폰 Tailscale IPv4로 한정한
TCP 8765 인바운드 허용 규칙을 사용하세요. 공용 네트워크 전체에 Java 접근을 허용하지 마세요.

화면은 최대 1280×720 연속 MJPEG, 기본 목표 15fps입니다(옵션 1–30fps).
실제 프레임률/지연은 CPU·업로드 속도·Tailscale 직접/릴레이 경로에 따라 달라집니다.
MJPEG는 H.264/HEVC보다 데이터 사용량이 많습니다. 모바일 데이터 요금제를 확인하세요.
느리면 `--fps=8`로 낮출 수 있습니다. 오디오와 H.264 하드웨어 인코딩은 제공하지 않습니다.
새 앱은 예전 서버 연결 시 기존 JPEG 폴링 모드로 자동 호환하지만, 개선된 스트리밍에는 새 서버가 필요합니다.

공식 안내: https://tailscale.com/docs/how-to/connect-to-devices
암호화: https://tailscale.com/docs/concepts/tailscale-encryption
주소 대역: https://tailscale.com/docs/reference/reserved-ip-addresses
