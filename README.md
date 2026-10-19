# AI Monitor

AI 사용량과 Azure 비용을 보는 로컬 대시보드 모음입니다. ai-master(`F:\sandbox\ai-master`)에서 분리했습니다.

| 파일 | 용도 | 주소 (hosts 미등록 시) |
|---|---|---|
| `hub.py` | 모니터 허브: 한 화면에서 시작·중지·재시작, 로그 보기 | http://ai-monitor.local (8770) |
| `usage_monitor.py` | ChatGPT/Codex·Claude 토큰 사용량과 한도 | http://usage-monitor.local (8767) |
| `cost_monitor.py` | Azure 일별 비용 (Cost Management, 읽기 전용) | http://cost-monitor.local (8769) |
| `cost_batch.py` | 최근 비용을 `cost_monitor.db`에 모아 두는 배치 (작업 스케줄러용) | - |
| `webserver.py` | 공통: hosts 기준 주소로 서버 띄우기, 크롬 열기 | - |

## 준비

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

## 실행

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
