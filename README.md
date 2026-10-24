# AI Monitor

PC용 로컬 대시보드와 독립형 Android 앱을 함께 관리하는 프로젝트입니다. PC 대시보드는 ai-master(`F:\sandbox\ai-master`)에서 분리했습니다.

- **PC 모니터**: AI 사용량·한도와 Azure 비용을 조회합니다. Python 허브로 실행합니다.
- **Android 앱 — Code Spend**: Claude Code·Codex만 대상으로 합니다. Azure 비용은 조회하지 않으며, PC 서버나 LAN 연결 없이 휴대폰에서 실행합니다.

## Android 앱 빠른 시작

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

휴대폰에 직접 설치할 APK는 빌드 후 `android-app/CodeSpend.apk`에 생성됩니다(Android 8.0 이상, 개인 설치용 디버그 서명).

앱의 **구독 계정** 탭은 공식 사용량·결제 페이지를 브라우저로 열며, 구독 수치를 앱 카드로 자동 수집하지 않습니다. **API 비용** 탭은 관리자 API 키와 Claude Code 전용 워크스페이스 / Codex 전용 프로젝트를 설정해야 조회할 수 있습니다. API 비용 합계에 개인 구독료는 포함되지 않습니다.

에뮬레이터 도구는 이 PC에 설치되어 있지만 Git에는 포함되지 않습니다. 다른 PC의 준비 사항과 로그인·빌드·오류 해결 방법은 [Android 앱 README](android-app/README.md)를 참고하세요.

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

Android의 SDK·Gradle 도구(`android-app/.toolchain/`), 빌드 결과, APK, 에뮬레이터 로그·검사 자료(`android-app/.emulator/`), 서명 키도 제외합니다. 실제 인증 정보는 `.env` 또는 앱 내 입력으로만 관리하고 README나 소스에 넣지 않습니다.
