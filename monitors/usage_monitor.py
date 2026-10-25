"""Local dashboard for ChatGPT/Codex token usage limits.

- ChatGPT: ~/.codex/sessions 로그의 token_count 이벤트에서 한도 사용률을 읽는다.
- 최근 5시간 / 7일 토큰 사용량을 로컬 세션 기록으로 집계한다.
"""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse

from common.server import favicon, local_server, open_chrome
from providers.tavily_usage import usage as tavily_usage
from providers.elevenlabs_usage import usage as elevenlabs_usage

HOME = Path.home()
CODEX_SESSIONS = HOME / ".codex" / "sessions"
PUBLIC_HOST = "usage-monitor.local"
CLAUDE_CREDENTIALS = HOME / ".claude" / ".credentials.json"
CLAUDE_USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
API_CACHE_SECONDS = 60
WINDOWS = {"5h": 5 * 3600, "7d": 7 * 86400}


CLAUDE_LABELS = {
    "five_hour": "5시간 세션",
    "seven_day": "주간 (전체 모델)",
    "seven_day_opus": "주간 (Opus)",
    "seven_day_sonnet": "주간 (Sonnet)",
    "seven_day_oauth_apps": "주간 (OAuth 앱)",
}

_claude_cache: dict = {"at": 0.0, "value": None}


def parse_time(value: str) -> float:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def iso(ts: float | None) -> str | None:
    return datetime.fromtimestamp(ts, timezone.utc).isoformat() if ts else None


def empty_totals() -> dict:
    return {k: {"total": 0, "output": 0, "requests": 0} for k in WINDOWS}


def add_usage(totals: dict, ts: float, now: float, total: int, output: int):
    for key, span in WINDOWS.items():
        if now - ts <= span:
            totals[key]["total"] += total
            totals[key]["output"] += output
            totals[key]["requests"] += 1


def claude_limits() -> dict:
    """Read Claude Code's local OAuth quota endpoint.

    This is the endpoint used by Claude Code for subscription usage limits;
    it is separate from the billable Anthropic API usage endpoint.
    """
    now = time.time()
    if _claude_cache["value"] and now - _claude_cache["at"] < API_CACHE_SECONDS:
        return _claude_cache["value"]
    result = {"limits": [], "plan": None, "error": None}
    try:
        oauth = json.loads(CLAUDE_CREDENTIALS.read_text(encoding="utf-8"))["claudeAiOauth"]
        result["plan"] = oauth.get("subscriptionType")
        if oauth.get("expiresAt", 0) / 1000 < now:
            raise PermissionError("Claude Code OAuth 토큰이 만료되었습니다. Claude Code를 다시 실행해 갱신하세요.")
        req = urllib.request.Request(CLAUDE_USAGE_URL, headers={
            "Authorization": f"Bearer {oauth['accessToken']}",
            "anthropic-beta": "oauth-2025-04-20",
            "Content-Type": "application/json",
        })
        with urllib.request.urlopen(req, timeout=10) as response:
            payload = json.loads(response.read().decode("utf-8"))
        for key, value in payload.items():
            if isinstance(value, dict) and value.get("utilization") is not None:
                result["limits"].append({
                    "id": key,
                    "label": CLAUDE_LABELS.get(key, key),
                    "used": float(value["utilization"]),
                    "resets_at": value.get("resets_at"),
                })
        extra = payload.get("extra_usage") or {}
        if extra.get("is_enabled") and extra.get("utilization") is not None:
            result["limits"].append({
                "id": "extra_usage", "label": "추가 사용량 (월)",
                "used": float(extra["utilization"]), "resets_at": None,
            })
    except FileNotFoundError:
        result["error"] = f"Claude Code 자격증명 파일이 없습니다: {CLAUDE_CREDENTIALS}"
    except PermissionError as exc:
        result["error"] = str(exc)
    except urllib.error.HTTPError as exc:
        result["error"] = "Claude 사용량 인증에 실패했습니다. Claude Code를 다시 실행해 토큰을 갱신하세요." if exc.code == 401 else f"Claude 사용량 API 오류: HTTP {exc.code}"
    except (urllib.error.URLError, TimeoutError) as exc:
        result["error"] = f"Claude 사용량 API 연결 실패: {exc}"
    except (KeyError, json.JSONDecodeError, UnicodeDecodeError) as exc:
        result["error"] = f"Claude 사용량 응답을 해석하지 못했습니다: {exc}"
    except Exception as exc:
        result["error"] = f"Claude 사용량 조회 실패: {exc}"
    _claude_cache.update(at=now, value=result)
    return result


# ---------------------------------------------------------------- ChatGPT / Codex
# ---------------------------------------------------------------- Codex

def codex_usage(now: float) -> dict:
    totals = empty_totals()
    latest = None  # (ts, rate_limits)
    last = None
    if CODEX_SESSIONS.is_dir():
        for file in CODEX_SESSIONS.rglob("*.jsonl"):
            recent = now - file.stat().st_mtime <= WINDOWS["7d"]
            if not recent and latest:
                continue
            prev_total = None
            with file.open(encoding="utf-8", errors="replace") as fh:
                for line in fh:
                    if '"token_count"' not in line:
                        continue
                    try:
                        row = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    payload = row.get("payload") or {}
                    if payload.get("type") != "token_count" or not row.get("timestamp"):
                        continue
                    ts = parse_time(row["timestamp"])
                    if payload.get("rate_limits") and (not latest or ts > latest[0]):
                        latest = (ts, payload["rate_limits"])
                    info = payload.get("info") or {}
                    cumulative = (info.get("total_token_usage") or {}).get("total_tokens")
                    # 같은 누적값이 반복 기록되면 새 요청이 아니므로 건너뛴다.
                    if cumulative is None or cumulative == prev_total:
                        continue
                    prev_total = cumulative
                    turn = info.get("last_token_usage") or {}
                    last = max(last or ts, ts)
                    add_usage(totals, ts, now, turn.get("total_tokens", 0) or 0, turn.get("output_tokens", 0) or 0)

    result = {"limits": [], "plan": None, "as_of": None, "error": None,
              "local": {"windows": totals, "last_activity": iso(last)}}
    if not latest:
        result["error"] = f"Codex 세션 기록이 없습니다: {CODEX_SESSIONS}"
        return result
    ts, limits = latest
    result["plan"] = limits.get("plan_type")
    result["as_of"] = iso(ts)
    for key, label in (("primary", None), ("secondary", None)):
        window = limits.get(key)
        if not window:
            continue
        minutes = window.get("window_minutes") or 0
        label = "5시간 세션" if minutes == 300 else "주간" if minutes == 10080 else f"{minutes // 60}시간 창"
        resets_at = window.get("resets_at")
        expired = bool(resets_at and resets_at < now)
        result["limits"].append({
            "id": key,
            "label": label,
            # 리셋 시각이 지났으면 기록 이후 사용분이 없으므로 0%로 본다.
            "used": 0.0 if expired else float(window.get("used_percent") or 0),
            "resets_at": None if expired else iso(resets_at),
            "note": "기록 이후 리셋됨" if expired else None,
        })
    return result




def collect() -> dict:
    now = time.time()
    return {
        "now": iso(now),
        "claude": claude_limits(),
        "chatgpt": codex_usage(now),
        "tavily": tavily_usage(),
        "elevenlabs": elevenlabs_usage(),
    }


HTML_PAGE = r'''<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AI Usage Monitor</title>__FAVICON__<style>
:root{--ink:#172033;--muted:#718096;--line:#e5eaf0;--panel:#fff;--bg:#eef2f7;--track:#e8edf3;--ok:#2f9e6b;--warn:#d98a1c;--bad:#d64545;--claude:#d97757;--chatgpt:#10a37f}
*{box-sizing:border-box}body{margin:0;color:var(--ink);font:14px/1.6 "Segoe UI","Malgun Gothic",sans-serif;background:var(--bg)}
.top{height:58px;background:#13213a;color:#fff;display:flex;align-items:center;padding:0 24px;gap:14px}.mark{width:28px;height:28px;border-radius:8px;background:#42b883;display:grid;place-items:center;font-weight:800}.brand{font-size:16px;font-weight:700}.crumb{color:#aebbd0;font-size:12px}.top .sp{flex:1}
button,select{font:inherit;border:1px solid #33476b;background:transparent;color:#fff;border-radius:8px;padding:5px 12px;cursor:pointer}button:hover,select:hover{background:#1f3152}select option{color:var(--ink);background:#fff}
.wrap{max-width:1180px;margin:0 auto;padding:12px 16px 16px}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;align-items:start}
.card{min-width:0;background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px 16px;border-top:4px solid var(--c)}
.head{display:flex;align-items:center;gap:8px;margin-bottom:8px}.logo{width:28px;height:28px;border-radius:7px;background:var(--c);color:#fff;display:grid;place-items:center;font-weight:800}.head h2{margin:0;font-size:17px}.plan{margin-left:auto;font-size:12px;background:#f1f4f8;border-radius:999px;padding:2px 10px;color:var(--muted);text-transform:capitalize}
.limit{padding:7px 0;border-top:1px solid var(--line)}.limit:first-of-type{border-top:0}
.row{display:flex;align-items:baseline;gap:8px}.lbl{font-weight:600}.left{margin-left:auto;font-size:24px;font-weight:700;font-variant-numeric:tabular-nums}.left small{font-size:12px;color:var(--muted);font-weight:500;margin-left:2px}
.bar{height:8px;background:var(--track);border-radius:4px;overflow:hidden;margin:4px 0 3px}.bar i{display:block;height:100%;border-radius:4px;transition:width .4s}
.sub{display:flex;flex-wrap:wrap;justify-content:space-between;gap:2px 8px;color:var(--muted);font-size:12px;font-variant-numeric:tabular-nums}
.err{background:#fdecec;color:#9b2c2c;border-radius:8px;padding:10px 12px;font-size:13px}
.foot{color:var(--muted);font-size:12px;margin-top:6px}.status{color:var(--muted);font-size:12px;text-align:right;margin-bottom:8px}
@media(max-width:820px){.grid{grid-template-columns:1fr}}
@media(max-width:640px){.top{height:auto;min-height:58px;flex-wrap:wrap;padding:10px 12px;gap:8px}.top .crumb{display:none}.wrap{padding:10px 12px 16px}.card{padding:12px}.sub span{overflow-wrap:anywhere}}
</style></head><body>
<header class="top"><div class="mark">U</div><div class="brand">AI Usage Monitor</div><div class="crumb">Claude · ChatGPT · Tavily · ElevenLabs 사용량</div><div class="sp"></div><select id="refreshInterval" title="자동 갱신 주기"><option value="5">5분</option><option value="10">10분</option><option value="30">30분</option><option value="60" selected>60분</option><option value="120">120분</option><option value="0">수동</option></select><button id="refresh">새로고침</button></header>
<div class="wrap"><div class="status" id="status">불러오는 중…</div><div class="grid">
<section class="card" style="--c:var(--claude)" id="claude"></section><section class="card" style="--c:var(--chatgpt)" id="chatgpt"></section>
<section class="card websearch" style="--c:#84704c" id="tavily"></section>
<section class="card" style="--c:#4655b8" id="elevenlabs"></section>
</div></div>
<script>
const $=s=>document.querySelector(s);let data=null,refreshTimer=null;const refreshKey='ai-usage-monitor-refresh-minutes';
function refreshMinutes(){return Number($('#refreshInterval').value)||0}
function refreshText(){const m=refreshMinutes();return m?m+'분마다 자동 갱신':'수동 갱신'}
function configureRefresh(){if(refreshTimer)clearInterval(refreshTimer);const m=refreshMinutes();localStorage.setItem(refreshKey,String(m));if(m)refreshTimer=setInterval(load,m*60000)}
function esc(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function until(iso){if(!iso)return '';let s=Math.max(0,(new Date(iso)-Date.now())/1000);const d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60);return (d?d+'일 ':'')+(d||h?h+'시간 ':'')+m+'분 후 리셋'}
function clock(iso){return iso?new Date(iso).toLocaleString('ko-KR',{month:'numeric',day:'numeric',weekday:'short',hour:'2-digit',minute:'2-digit'}):''}
function ago(iso){if(!iso)return '기록 없음';const m=Math.round((Date.now()-new Date(iso))/60000);return m<1?'방금 전':m<60?m+'분 전':m<1440?Math.round(m/60)+'시간 전':Math.round(m/1440)+'일 전'}
function color(used){return used>=90?'var(--bad)':used>=70?'var(--warn)':'var(--ok)'}
function limits(list){return list.map(l=>{const left=Math.max(0,100-l.used),precision=l.precision??0;return `<div class="limit"><div class="row"><span class="lbl">${esc(l.label)}</span><span class="left">${left.toFixed(precision)}%<small>남음</small></span></div><div class="bar"><i style="width:${Math.min(100,left)}%;background:${color(l.used)}"></i></div><div class="sub"><span>${l.used.toFixed(precision)}% 사용${l.note?' · '+esc(l.note):''}</span><span>${l.resets_at?esc(until(l.resets_at))+' · '+esc(clock(l.resets_at)):''}</span></div></div>`}).join('')}
function card(el,name,logo,d,extra){el.innerHTML=`<div class="head"><div class="logo">${logo}</div><h2>${name}</h2>${d.plan?`<span class="plan">${esc(d.plan)}</span>`:''}</div>${d.error?`<div class="err">${esc(d.error)}</div>`:''}${limits(d.limits||[])}${extra||''}`}
async function load(){$('#status').textContent='불러오는 중…';try{const r=await fetch('/api/usage');data=await r.json();render();$('#status').textContent='업데이트: '+new Date().toLocaleTimeString('ko-KR')+' · '+refreshText()}catch(e){$('#status').textContent='불러오기 실패: '+e.message}}
$('#refresh').onclick=load;const saved=localStorage.getItem(refreshKey);if(saved!==null&&[5,15,30,60,120,0].includes(Number(saved)))$('#refreshInterval').value=saved;$('#refreshInterval').onchange=()=>{configureRefresh();load()};configureRefresh();load();setInterval(render,30000);
function creditCard(id,title,logo,value,limitLabel,note){const d=value||{limits:[],error:title+' 사용량을 불러오지 못했습니다.'},c=d.credits;card($(id),title,logo,d,c?`<div class="foot">잔여 <b>${Number(c.remaining).toLocaleString('ko-KR')} 크레딧</b> · ${limitLabel} ${Number(c.limit).toLocaleString('ko-KR')} 크레딧${note?' · '+note:''}</div><div class="foot">사용량 확인: ${esc(clock(d.as_of))} · API 조회는 최대 1분 간격</div>`:'')}
function render(){if(!data)return;card($('#claude'),'Claude','C',data.claude,'');card($('#chatgpt'),'ChatGPT','G',data.chatgpt,data.chatgpt.as_of?`<div class="foot">최근 한도 갱신: ${esc(clock(data.chatgpt.as_of))} (${esc(ago(data.chatgpt.as_of))})</div>`:'');creditCard('#tavily','Tavily 웹검색','T',data.tavily,'월 한도','검색 횟수가 아닌 크레딧 기준');creditCard('#elevenlabs','ElevenLabs','Ⅱ',data.elevenlabs,'구독 한도','')}
</script></body></html>'''


class Handler(BaseHTTPRequestHandler):
    def send_body(self, data: bytes, content_type: str):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/":
            self.send_body(HTML_PAGE.replace("__FAVICON__", favicon("U", "#10a37f")).encode("utf-8"), "text/html; charset=utf-8")
        elif path == "/api/usage":
            self.send_body(json.dumps(collect(), ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")
        else:
            self.send_error(404)

    def log_message(self, format: str, *args):
        pass


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    port = int(args[0]) if args else 8767
    server = local_server(port, Handler, PUBLIC_HOST)
    print(f"AI usage monitor: {server.url}")
    if "--no-browser" not in sys.argv:
        open_chrome(server.url)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()


if __name__ == "__main__": main()
