# Code Spend — Android

Claude는 공식 웹페이지에서 로그인해 사용량을 확인하고, Codex는 앱 카드에서 구독 한도를 조회하는 독립형 Android 앱입니다. PC 서버는 필요 없습니다. 관리자 API 키, 워크스페이스 ID, 프로젝트 ID는 필요 없습니다. Azure/API 청구 비용이나 구독 청구 금액을 조회하는 앱은 아닙니다.

[프로젝트 전체 안내](../README.md)

명령은 별도 안내가 없으면 프로젝트 루트에서 실행합니다. `F:\sandbox\ai-monitor`는 이 PC의 예시 경로이며, 다른 PC에서는 실제 저장소 위치로 바꾸세요.

## 설치

현재 버전은 **4.5**입니다. 빌드된 APK는 `android-app/CodeSpend.apk`이며, 동일 원본은 `android-app/app/build/outputs/apk/debug/app-debug.apk`입니다(프로젝트 루트 기준). 개인 설치용 디버그 서명이며 Play 스토어 배포용 서명은 아닙니다. APK는 Git에 포함하지 않으므로 저장소만 받았다면 먼저 빌드해야 합니다.

### 실제 휴대폰에 옮겨 설치

1. 이 PC에서 다음 명령으로 APK가 선택된 탐색기를 엽니다. 채팅의 로컬 APK 링크는 다운로드 버튼이 아니며 IDE에서 열리지 않을 수 있습니다.

   ```powershell
   explorer.exe /select,"F:\sandbox\ai-monitor\android-app\CodeSpend.apk"
   ```

2. USB로 휴대폰을 연결하고 파일 전송을 선택해 `CodeSpend.apk`를 휴대폰 **Download** 폴더에 복사합니다.
3. 휴대폰 파일 관리자에서 APK를 눌러 설치합니다. 필요하면 해당 파일 관리자에만 출처를 알 수 없는 앱 설치를 허용합니다. Windows에서 APK를 눌러 Android 앱을 실행할 수는 없습니다.
4. Code Spend를 열고 아래의 Claude/Codex 인증 연결을 진행합니다. APK에 PC의 인증정보가 들어 있지는 않습니다.

기존 Code Spend가 설치되어 있다면 이 PC의 같은 서명으로 만든 APK는 덮어써서 업데이트할 수 있습니다. 서명이 다르면 업데이트할 수 없습니다. 이 경우 무작정 기존 앱을 삭제하지 마세요. 삭제하면 기기에 저장한 인증·사용량 자료도 지워집니다.

### 소스에서 빌드

Android 8.0 이상 휴대폰에 APK를 전달해 설치합니다. 직접 빌드하려면 JDK 17, Android SDK 35가 필요합니다.

```powershell
cd F:\sandbox\ai-monitor
.\android-app\build-apk.ps1
# USB 디버깅으로 연결한 기기에 설치:
.\android-app\build-apk.ps1 -Install
```

Android Studio에서는 이 폴더를 열고 실행할 수 있습니다. Gradle Wrapper 8.9와 Android Gradle Plugin 8.7.3을 사용합니다.

### 버전 4.5: Claude 공식 웹 로그인과 간결한 화면

Claude의 토큰 가져오기·앱 내 직접 조회는 중단했습니다. 공식 웹에서 로그인하고 확인하며, 브라우저의 세션을 이용합니다. 웹 수치는 앱 카드에 연동되지 않습니다. 기존 Claude 자료는 자동 삭제하지 않으며 카드의 `⋮` 메뉴에서 삭제할 수 있습니다. Codex 직접 조회는 그대로 유지합니다. 설명 문구·카드 간격을 줄이고 새로고침은 상단으로 옮겼습니다.

공식 사용량 페이지 버튼은 일반 웹 브라우저만 찾아 해당 브라우저를 명시적으로 실행합니다. ChatGPT·Claude 앱의 지원 링크 설정을 바꾸지 않아도 되며, 브라우저가 없거나 열 수 없으면 공개 페이지 주소 복사를 안내합니다. 여러 브라우저 중 기본 브라우저가 없으면 앱에서 브라우저를 선택합니다.

Codex 웹 주소는 `https://chatgpt.com/codex/settings/usage`, Claude는 `https://claude.ai/settings/usage`입니다. 브라우저에는 인증 토큰을 전달하지 않습니다. **브라우저에서는 해당 서비스에 별도로 로그인**해야 하며, Codex 앱 카드에 사용량이 보이는 것과 브라우저의 로그인 상태는 별개입니다.

### 버전 4.0에서 도입한 기능

- Codex의 기본 연결을 JSONL 기록 가져오기에서 `auth.json`을 이용한 현재 한도 직접 조회로 변경했습니다.
- Codex는 화면이 열려 있는 동안 1분마다 갱신합니다. Claude는 공식 웹에서 확인합니다. 앱이 닫혀 있을 때 주기적으로 조회하는 백그라운드 서비스는 아닙니다.
- 갱신 주기는 앱이 활성화된 포그라운드 상태 기준이며, 화면 잠금·다른 앱 전환 중에는 주기 조회를 일시 중지합니다.
- JSONL 가져오기는 선택적인 오프라인 기록 모드로 남겨 현재 한도 조회와 구분합니다.
- 관리자 API 키·워크스페이스·프로젝트 ID 입력란을 제거했습니다. API 비용과 Azure 비용은 조회하지 않습니다.

## 에뮬레이터에서 실행

이 PC에는 Android Emulator와 Google Play 포함 Android 15(API 35) 이미지가 설치되어 있습니다. 다음 명령으로 가상 휴대폰을 열고 APK를 설치·실행합니다.

```powershell
cd F:\sandbox\ai-monitor
.\android-app\run-emulator.ps1
# 소스를 수정한 뒤 다시 빌드·실행:
.\android-app\run-emulator.ps1 -Rebuild
# 기존 가상기기가 멈췄을 때 데이터 삭제 없이 재시작:
.\android-app\run-emulator.ps1 -Restart
# 실행 중인 프로젝트 에뮬레이터의 기본 UI 자동 검사:
.\android-app\test-emulator.ps1
```

첫 부팅은 시간이 걸릴 수 있습니다. 스크립트가 부팅 완료를 기다린 뒤 앱을 실행합니다. 이미 열려 있는 프로젝트 에뮬레이터는 ADB에 등록되기 전이라도 프로세스를 감지해 재사용합니다. 동시에 실행한 다른 런처는 중복 기기를 띄우지 않고 안내 후 종료합니다. `-Rebuild`를 생략하면 기존 APK를 사용합니다(APK가 없으면 빌드). `-Restart`는 이 프로젝트 가상기기만 종료·재시작하며 앱·로그인 데이터를 삭제하지 않습니다.

가상기기 이름은 `CodeSpend_API35`, 기본 기기 번호는 `emulator-5554`입니다. 데이터는 `%LOCALAPPDATA%\CodeSpend\avd`에 유지합니다. 별도 경로는 `CODE_SPEND_AVD_HOME` 환경변수로 지정할 수 있습니다. CPU는 WHPX 가속, 그래픽은 이 PC의 OpenGL 호환성을 위해 소프트웨어 렌더링을 사용합니다. 그래픽 소프트웨어 렌더링은 CPU 가속을 끄는 `-Software` 옵션과 다릅니다.

실행 창을 닫으면 에뮬레이터가 종료됩니다. 앱 자료는 가상기기에 남습니다. 노트북 키보드 입력은 기본 활성화하며 필기 입력 도구 모음과 화면 키보드는 기본 숨김입니다. Google 계정 추가나 Chrome 로그인 없이 앱의 사용량 카드를 이용할 수 있습니다.

### 다른 PC에서 준비할 것

설치된 도구와 가상기기는 Git에 포함되지 않으며, 실행 스크립트가 SDK를 자동 다운로드하지는 않습니다. 다른 PC에서는 다음을 먼저 준비해야 합니다.

- JDK 17 및 Android SDK 위치를 가리키는 `ANDROID_HOME`
- SDK 패키지: `platform-tools`, `emulator`, `cmdline-tools;latest`, `platforms;android-35`, `build-tools;34.0.0`
- 가상기기 이미지: `system-images;android-35;google_apis_playstore;x86_64`
- Windows에서 BIOS/UEFI 가상화와 Windows Hypervisor Platform(WHPX)을 사용할 수 있는 환경

현재 스크립트의 가상기기 이미지는 Windows x86_64 PC용입니다. 처음 빌드할 때는 Gradle과 빌드 의존성을 내려받을 인터넷 연결도 필요합니다.

### 실행 오류 확인

| 증상 | 확인할 내용 |
|---|---|
| `still starting` / `offline` | 기존 에뮬레이터의 부팅을 기다린 뒤 다시 실행합니다. |
| `Running multiple emulators with the same AVD` / 부팅이 멈춤 | 같은 가상기기의 기존 프로세스가 남아 있을 수 있습니다. `run-emulator.ps1 -Restart`로 해당 가상기기만 다시 시작합니다. 데이터나 잠금 파일을 직접 삭제할 필요는 없습니다. |
| `Another launcher is already starting` | 다른 터미널의 실행이 끝날 때까지 기다립니다. 동시에 실행해도 두 번째 가상기기는 만들지 않습니다. |
| `Hardware acceleration is unavailable` | Windows 가상화 기능을 확인합니다. 기능을 방금 켰다면 재부팅이 필요할 수 있습니다. 스크립트는 PC를 재부팅하지 않습니다. |
| OpenGL 오류 / 창이 바로 종료됨 | 기본 소프트웨어 그래픽 설정으로 실행하고 터미널에 출력된 `.stdout.log`, `.stderr.log`를 확인합니다. 두 로그의 마지막 부분도 오류 발생 시 터미널에 표시됩니다. |
| 기본 포트를 다른 기기가 사용 중 | `run-emulator.ps1 -Port 5556`으로 실행하고, UI 검사는 `test-emulator.ps1 -Device emulator-5556`으로 실행합니다. |
| PowerShell에서 스크립트 실행이 차단됨 | 신뢰하는 프로젝트 코드인지 확인한 뒤 `powershell -NoProfile -ExecutionPolicy Bypass -File .\android-app\run-emulator.ps1`로 해당 실행에만 적용합니다. |

위 경로와 명령은 프로젝트 루트(`F:\sandbox\ai-monitor`) 기준입니다. 실행 로그는 `android-app/.emulator/emulator-날짜-시각-PID.stdout.log` / `.stderr.log`에 매번 별도로 저장하여 이전 실패 로그를 덮어쓰지 않습니다. UI 검사 자료도 `android-app/.emulator/` 아래에 저장되며 모두 Git에서 제외됩니다.

## Claude 구독 사용량 연결

1. **Claude 로그인 · 사용량 보기**를 누릅니다.
2. 열린 브라우저의 Claude 공식 페이지에서 로그인합니다.
3. 이후 같은 브라우저로 열면 유지된 로그인 세션을 이용합니다. 세션 만료·로그아웃·브라우저 자료 삭제 시에는 다시 로그인해야 합니다.

PC의 `.credentials.json` 전달은 필요 없습니다. 앱은 브라우저의 비밀번호·쿠키·토큰을 읽지 않으며 웹의 사용량을 앱 카드에 자동으로 옮기지 않습니다. 사용량은 공식 웹에서 확인하세요. 이전에 앱에 가져온 Claude 자료는 조회하지 않으며 `⋮ → 저장된 Claude Code 자료 삭제`로 직접 지울 수 있습니다. PC의 모니터와 원본 인증 파일은 변경하지 않았습니다.

## Codex 현재 사용량 연결

앱의 **Codex 인증 가져오기 → auth.json 파일 선택**에서 PC의 `%USERPROFILE%\.codex\auth.json`을 한 번 가져옵니다. `CODEX_HOME`을 따로 설정했다면 그 폴더의 `auth.json`입니다. 직접 입력하려면 `tokens.access_token` 값 또는 인증 JSON을 사용합니다. `config.toml`이나 일반 API 키는 사용하지 않습니다.

실제 휴대폰에서는 다음 순서로 연결합니다.

1. PC의 Codex에 ChatGPT 구독 계정으로 로그인합니다.
2. USB 등 본인이 관리하는 경로로 `auth.json`을 휴대폰에 복사합니다. 인증 파일을 메신저·GitHub에 올리지 마세요.
3. 앱에서 위 파일을 선택합니다. 앱은 `tokens.access_token`과 선택적인 `tokens.account_id`만 암호화해 저장합니다.
4. 가져오기가 끝나면 휴대폰 공유 폴더에 복사한 인증 파일을 직접 삭제합니다.

이후 휴대폰이 `https://chatgpt.com/backend-api/wham/usage`에서 현재 사용 한도와 초기화 시각을 조회합니다. **앱 화면이 열려 있는 동안 1분마다 갱신**하며, 화면으로 돌아왔을 때 갱신 시점이 지났으면 다시 조회합니다. 새로고침 버튼으로 직접 조회할 수도 있습니다. PC를 켜 두거나 JSONL 기록을 다시 추출할 필요는 없습니다.

이 주소는 Codex 내부 사용량 주소로, 공개적으로 보장된 모바일 API가 아닙니다. 접근 제한·변경 가능성이 있습니다. `401`/`403` 응답이면 자동 재시도를 멈추고 인증을 다시 가져오도록 안내합니다. `429` 응답이면 서버의 대기 시간을 따르고, 연결 오류이면 간격을 늘려 재시도합니다. 마지막 성공 시각과 오류를 함께 표시하며 오래된 수치를 새로 조회한 것처럼 바꾸지 않습니다.

`refresh_token`·`id_token`은 저장하거나 사용하지 않습니다. 로그인 자동 갱신은 하지 않으므로 **access token 만료 시에는 PC에서 Codex 로그인을 갱신한 뒤 인증 파일을 다시 가져와야 합니다.** Codex 앱 카드의 인증은 공식 웹 로그인과 별개입니다.

### 선택 사항: 오프라인 기록 가져오기

인증을 연결하지 않고 기록만 확인하려면 **기록 가져오기 (오프라인)**에서 `%USERPROFILE%\.codex\sessions` 아래의 JSONL 파일을 선택합니다. 이 방식은 현재 한도 직접 조회와 별개입니다.

- `payload.type = token_count` 이벤트의 `rate_limits`에서 5시간·주간 사용률과 초기화 시각을 읽습니다.
- `last_token_usage`로 최근 5시간·7일 토큰 수를 집계합니다. 대화 내용은 저장하지 않습니다.
- 선택한 파일에 없는 세션의 사용량은 포함되지 않습니다. 전체 계정 토큰 합계가 아닐 수 있습니다.
- 스마트폰에는 PC 세션 파일이 자동 생성되지 않습니다. **오프라인 기록 모드만 가져온 시점의 값이며 자동 갱신되지 않습니다.** 현재 한도를 보려면 위의 Codex 인증 연결을 사용하세요.
- 초기화 시각이 지난 기록을 현재 잔여량 100%로 바꾸지 않습니다. 현재값 미확인으로 표시합니다.

PC에서 대화 내용 없이 사용량 기록만 추출하려면:

```powershell
python .\android-app\export-codex-usage.py
```

생성한 `android-app/.local-import/codex-usage.jsonl`을 휴대폰에 전달한 뒤 선택합니다. 원본 세션 기록은 수정하지 않으며, 생성 파일은 Git에서 제외됩니다.

## 이 PC 자료를 에뮬레이터로 가져오기

앱 설치·실행 후 다음을 실행합니다. 이 프로젝트의 디버그 APK가 필요합니다. 기본 인증 가져오기에는 Python이 필요하지 않습니다.

```powershell
.\android-app\import-pc-usage.ps1
# 이전 명령과 호환되는 옵션 (현재는 항상 Codex만 가져옴):
.\android-app\import-pc-usage.ps1 -SkipClaude
# 선택 사항: Codex 인증 대신 오프라인 기록 가져오기 (Python 필요):
.\android-app\import-pc-usage.ps1 -SkipClaude -LegacyRecords
```

이 도구는 프로젝트의 `CodeSpend_API35` 에뮬레이터만 대상으로 합니다. Codex의 최소 인증 항목만 메모리에서 추려 전달하며 Claude 인증은 읽거나 전달하지 않습니다. 인증 토큰을 명령줄, 공용 Download 폴더, 터미널 출력에 넣지 않고 앱 전용 임시 파일로 전달합니다. 앱은 필요한 항목을 암호화해 저장한 뒤 임시 파일을 삭제합니다. PC 서버나 LAN 공개는 필요 없습니다. PC의 인증 파일과 세션 로그는 변경하지 않습니다.

## 보안과 데이터 구분

- 필요한 토큰·사용량 요약은 Android Keystore AES-GCM으로 암호화해 기기에 저장합니다. Android 백업과 최근 앱 화면 캡처는 차단합니다.
- Codex 토큰은 지정된 HTTPS 사용량 주소에만 전송하며 다른 주소로 리디렉션하지 않습니다. Claude는 공식 브라우저 세션만 이용합니다. 추론·결제 요청은 하지 않습니다.
- 인증 파일을 휴대폰의 공유 폴더로 옮겼다면 가져온 후 그 복사본은 직접 삭제하세요. 앱의 자료 삭제는 PC 원본이나 공유 폴더 복사본을 지우지 않습니다. APK에는 PC의 인증정보를 포함하지 않습니다.
- 남은 한도 %, 토큰 수, 실제 청구 비용은 서로 다릅니다. 앱은 이를 금액으로 환산하지 않습니다.
- Claude의 공식 웹 버튼은 기본 조회 경로이고 Codex의 공식 웹 버튼은 보조 링크입니다. Chrome/Google 계정 추가는 필수가 아닙니다.

## 구현 근거와 검사

- PC 기준 구현: [usage_monitor.py](../monitors/usage_monitor.py)의 `claude_limits`, `codex_usage` (Codex 기록 해석). 모바일 현재 한도 직접 조회: [CodexUsageClient.java](app/src/main/java/local/aimonitor/CodexUsageClient.java).
- [OpenAI 공식 인증 문서](https://learn.chatgpt.com/docs/auth): 구독 인증과 API 키 인증의 구분, 인증 파일 보호
- [OpenAI App Server 문서](https://learn.chatgpt.com/docs/app-server): Codex 런타임의 계정·사용 한도 인터페이스. 위 내부 HTTPS 주소에 대한 공개 API 보장을 의미하지 않으며, 본 앱에서 PC 서버를 띄우지도 않습니다.

`build-apk.ps1`은 사용량 해석·인증·오류 처리 단위 테스트, Android Lint, APK 빌드를 실행합니다. `test-emulator.ps1`은 관리자 키/공간 ID 입력란이 없어졌는지, 토큰 입력 검증과 파일 선택·취소, 자료 유지 및 크래시 여부를 검사합니다. 실제 계정의 비밀번호를 자동 입력하지 않습니다.

웹 연결 회귀 검사는 API 35 프로젝트 에뮬레이터가 켜진 상태에서 다음과 같이 실행합니다.

```powershell
.\android-app\test-browser-links.ps1
```

이 검사는 별도 테스트 APK를 임시 설치해 ChatGPT·Claude 링크를 서비스 앱이 받는 상황을 재현합니다. 실제 화면의 공식 사용량 버튼을 눌러 브라우저와 전달 주소를 검사한 뒤 테스트 APK를 제거합니다. 테스트용 링크 처리기는 배포할 `CodeSpend.apk`에는 포함되지 않으며 기존 앱 인증·사용량 자료는 삭제하지 않습니다.

2026-10-24에 확인한 결과는 다음과 같습니다. 실제 휴대폰 전체에 대한 호환성 검증을 의미하지는 않습니다.

- Java 단위 테스트 129개 통과, Android Lint 오류 0개(경고 3개), APK 빌드·서명 검증 성공
- Codex 기록 추출기 Python 테스트 22개 통과
- API 35 에뮬레이터 UI 검사 통과 및 Codex 실제 계정 조회 성공
- 새로고침 버튼을 누르지 않은 상태에서 Codex 기준 시각 자동 갱신 확인
- 서비스 앱이 기본 링크 처리기로 지정된 상황에서도 Claude·Codex 공식 사용량 버튼이 Chrome으로 정확한 공개 주소를 여는 회귀 검사 통과
- APK·암호화 저장공간에 로컬 인증 토큰이 평문으로 포함되지 않았고, 앱 전용 가져오기 임시 파일이 제거된 것 확인

기록 추출기 테스트는 프로젝트 루트에서 다음과 같이 실행합니다.

```powershell
python -B -m unittest discover -s android-app -p test_export_codex_usage.py
```
