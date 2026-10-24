# AI Monitor

PC용 로컬 대시보드와 독립형 Android 앱을 함께 관리하는 프로젝트입니다. PC 대시보드는 ai-master(`F:\sandbox\ai-master`)에서 분리했습니다.

- **PC 모니터**: AI 사용량·한도와 Azure 비용을 조회합니다. Python 허브로 실행합니다.
- **Android 앱 — Code Spend**: Claude Code·Codex의 구독 사용량·한도를 표시합니다. Azure/API 청구 비용은 조회하지 않으며, PC 서버나 LAN 연결 없이 실행합니다.

## Android 앱 빠른 시작

현재 버전은 **Code Spend 4.0**입니다. Claude Code·Codex의 남은 구독 한도와 초기화 시각을 표시합니다. 구독 사용률을 금액으로 환산하거나 Azure/API 비용을 조회하지 않습니다.

아래 명령은 프로젝트 루트에서 실행합니다. `F:\sandbox\ai-monitor`는 이 PC의 경로이므로 다른 PC에서는 실제 저장소 위치로 바꾸세요.

### 내 스마트폰에 설치

이 PC에서 만든 APK 위치는 `F:\sandbox\ai-monitor\android-app\CodeSpend.apk`입니다. 채팅의 로컬 파일 링크가 열리지 않으면 PowerShell에서 다음 명령으로 파일 위치를 엽니다.

```powershell
explorer.exe /select,"F:\sandbox\ai-monitor\android-app\CodeSpend.apk"
```

APK를 USB로 휴대폰의 **Download 폴더에 복사 → 휴대폰 파일 관리자에서 APK 선택 → 설치**합니다. 필요한 경우 해당 파일 관리자에만 출처를 알 수 없는 앱 설치를 허용하세요. Windows에서는 APK를 직접 실행할 수 없습니다.

APK는 개인 설치용 디버그 서명이며 Android 8.0 이상을 지원합니다. **APK는 Git에 포함되지 않습니다.** 다른 PC에서 저장소를 받았다면 JDK 17·Android SDK 35를 준비하고 다음 명령으로 빌드합니다.

```powershell
.\android-app\build-apk.ps1
```

### PC에서 에뮬레이터 실행

이 PC에 설치된 에뮬레이터를 실행하려면 VS Code의 PowerShell 터미널에서 다음을 입력합니다.

```powershell
cd F:\sandbox\ai-monitor
.\android-app\run-emulator.ps1
```

Android 가상 휴대폰을 열고 APK를 설치한 뒤 Code Spend 앱까지 자동 실행합니다. 이미 실행 중인 프로젝트 에뮬레이터가 있으면 재사용합니다.

소스를 수정한 뒤 다시 빌드·설치·실행하려면:

```powershell
.\android-app\run-emulator.ps1 -Rebuild
```

실행 중인 앱의 기본 UI를 자동 검사하려면:

```powershell
.\android-app\test-emulator.ps1
```

### 인증 연결과 갱신

앱에서 **Claude 인증 가져오기**로 `.claude/.credentials.json` 또는 그 안의 `claudeAiOauth.accessToken`을 가져옵니다. **Codex 인증 가져오기**로 `.codex/auth.json`을 한 번 가져오면 휴대폰이 현재 한도를 직접 조회합니다. 관리자 API 키·워크스페이스·프로젝트 ID는 필요 없습니다.

화면이 열려 있는 동안 **Codex는 1분마다, Claude는 60분마다** 조회합니다. 다시 화면을 열었을 때 갱신 시점이 지났으면 바로 조회합니다. PC 서버나 반복적인 기록 추출은 필요 없습니다. 인증은 휴대폰에서 처음 한 번 가져와야 하며, 만료되면 다시 가져옵니다. 에뮬레이터에는 다음 도구로 필요한 인증 항목만 전달할 수 있습니다. 토큰은 공개 폴더나 명령줄이 아닌 앱 전용 저장공간으로 전달합니다.

갱신 주기는 앱이 활성화된 포그라운드 상태 기준입니다. 화면을 잠그거나 다른 앱으로 전환하면 주기 조회를 일시 중지합니다.

```powershell
.\android-app\import-pc-usage.ps1
```

기존 PC 파일은 변경하지 않습니다. 두 서비스의 내부 사용량 주소는 공개적으로 보장된 모바일 API가 아니므로 변경·접근 제한 가능성이 있습니다. 앱은 로그인 토큰 갱신을 자동으로 수행하지 않으며, 사용률을 금액으로 환산하지 않습니다. Codex 구독의 현재 한도와 예전에 가져온 로컬 토큰 합계는 다른 자료입니다.

휴대폰은 USB 등으로 인증 파일을 전달한 뒤 앱의 파일 선택기로 가져옵니다. 가져온 후 공유 폴더의 복사본은 직접 삭제하세요. 앱 자체의 브라우저 로그인 기능은 없으며 `.env`의 API 키로 구독 인증을 대신할 수 없습니다. 초기 인증 가져오기와 만료 시 재연결에는 PC 인증 파일이 필요하지만, 정상 조회 중에는 PC를 켜 두지 않아도 됩니다.

에뮬레이터 도구는 이 PC에 설치되어 있지만 Git에는 포함되지 않습니다. 다른 PC의 준비 사항과 로그인·빌드·오류 해결 방법은 [Android 앱 README](android-app/README.md)를 참고하세요.

2026-10-24 기준: Java 단위 테스트 120개, Codex 기록 추출기 테스트 22개 통과, Android Lint 오류 없음. API 35 에뮬레이터에서 인증 입력·파일 선택 UI와 Codex 직접 조회·자동 갱신을 확인했습니다. 모든 실제 휴대폰에서의 동작을 검증한 것은 아닙니다.

## PC 모니터 구성

| 파일 | 용도 | 주소 (hosts 미등록 시) |
|---|---|---|
| `hub.py` | 모니터 허브: 한 화면에서 시작·중지·재시작, 로그 보기 | http://ai-monitor.local (8770) |
| `usage_monitor.py` | ChatGPT/Codex·Claude 토큰 사용량과 한도 | http://usage-monitor.local (8767) |
| `cost_monitor.py` | Azure 일별 비용 (Cost Management, 읽기 전용) | http://cost-monitor.local (8769) |
| `cost_batch.py` | 최근 비용을 `cost_monitor.db`에 모아 두는 배치 (작업 스케줄러용) | - |
| `webserver.py` | 공통: hosts 기준 주소로 서버 띄우기, 크롬 열기 | - |

## PC 모니터 준비

- Python 3.10 이상. 외부 패키지는 쓰지 않습니다.
- 비용 모니터는 az CLI 로그인을 씁니다(`az login`). 구독에 Cost Management Reader 이상 권한이 필요합니다.
- 사용량 모니터는 `~/.codex/sessions` 기록과 `~/.claude/.credentials.json`을 읽습니다.

## 포트 없는 주소 (hosts)

hosts 파일은 포트를 정할 수 없으므로 서버마다 다른 루프백 IP의 80 포트를 씁니다. 서버는 시작할 때 hosts를 읽어 자기 이름에 적힌 IP로 뜹니다(`webserver.py`).

| hosts에 적힌 값 | 서버가 뜨는 곳 | 주소 |
|---|---|---|
| 없음 | 127.0.0.1:포트 | `http://127.0.0.1:8769` |
| `127.0.0.1 cost-monitor.local` | 127.0.0.1:포트 | `http://cost-monitor.local:8769` |
| `127.0.87.69 cost-monitor.local` | 127.0.87.69:80 | `http://cost-monitor.local` |

관리자 PowerShell에서 `C:\Windows\System32\drivers\etc\hosts`에 아래를 추가하고 `ipconfig /flushdns`를 실행합니다.

```
127.0.87.67 usage-monitor.local
127.0.87.69 cost-monitor.local
127.0.87.70 ai-monitor.local
```

127.0.87.64~66·68은 ai-master의 서버들이 씁니다.

## PC 모니터 실행

### 허브로 실행 (권장)

```powershell
cd F:\sandbox\ai-monitor
python hub.py                # 허브를 켜고 크롬으로 연다
python hub.py --start-all    # 꺼져 있는 모니터를 모두 시작
python hub.py --restart-all  # 모두 다시 시작 (중복 실행·예전 주소로 뜬 서버 정리)
python hub.py --no-browser   # 크롬을 열지 않음 (다른 옵션과 함께 사용)
```

- 모니터는 창 없이 뜨고 출력은 `logs/<서버>.log`에 남습니다. 허브를 꺼도(Ctrl+C) 모니터는 계속 돕니다.
- 허브 콘솔에는 시각과 함께 시작 설정, 시작·중지 요청, 생성·종료한 PID, 상태 변화, 오류가 찍힙니다.
- 허브 코드는 ai-master의 `hub.py`와 같고 위쪽 설정(이름, 서버 목록, import)만 다릅니다.

허브가 이미 떠 있으면 브라우저로 http://ai-monitor.local 을 열면 됩니다. 끄려면 다음을 실행합니다.

```powershell
Get-NetTCPConnection -LocalAddress 127.0.87.70 -LocalPort 80 -State Listen | ForEach-Object { taskkill /PID $_.OwningProcess /T /F }
```

### 직접 실행

```powershell
python usage_monitor.py                         # 기본 8767
python cost_monitor.py --subscription wonjy     # 기본 8769, 구독 생략 시 az 현재 구독
python cost_batch.py                            # 최근 7일 비용 수집
```

## Git에 올리지 않는 것

`.env`, `.azure/`, `.azure-cli/`(Azure 로그인 정보), `cost_monitor.db`(비용 데이터), `logs/`, `*.log`, `.vscode-data/`, `.vscode-extensions/`는 `.gitignore`로 제외합니다.

Android의 SDK·Gradle 도구(`android-app/.toolchain/`), 빌드 결과, APK, 에뮬레이터 로그·검사 자료(`android-app/.emulator/`), 가져오기 자료(`android-app/.local-import/`), 서명 키도 제외합니다. `.env.example`은 실제 값이 없는 공개용 템플릿입니다. 실제 `.env`와 Claude/Codex 인증 파일은 로컬에서만 관리하고 README나 소스에 넣지 않습니다. 앱은 필요한 인증 항목만 기기에 암호화해 저장합니다.
