"""Tavily 읽기 전용 사용량 조회. API 키와 원본 응답은 화면에 전달하지 않는다."""

import hashlib
import json
import math
import os
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

USAGE_URL = "https://api.tavily.com/usage"
ENV_PATH = Path(__file__).with_name(".env")
CACHE_SECONDS = 60
_cache = {}
_lock = threading.Lock()


def read_api_key(setting):
    """환경변수 우선, 없으면 프로젝트 .env의 해당 항목만 읽는다."""
    if setting in os.environ:
        return os.environ[setting].strip()
    if not ENV_PATH.exists():
        return ""
    for line in ENV_PATH.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if line.startswith("export "):
            line = line[7:].lstrip()
        name, separator, value = line.partition("=")
        if separator and name.strip() == setting:
            value = value.strip()
            if value[:1] in ("'", '"'):
                quote = value[0]
                closing = value.find(quote, 1)
                if closing < 0:
                    raise ValueError("Invalid key configuration")
                return value[1:closing].strip()
            return value.split(" #", 1)[0].strip()
    return ""


def api_key():
    return read_api_key("TAVILY_API_KEY")


def _number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("Invalid credit count")
    if not math.isfinite(value) or value < 0:
        raise ValueError("Invalid credit count")
    return value


def normalize(payload):
    account = payload["account"]
    used = _number(account["plan_usage"])
    limit = _number(account["plan_limit"])
    plan = account.get("current_plan")
    if not isinstance(plan, str):
        raise ValueError("Invalid plan")
    remaining = max(0, limit - used)
    return {
        "plan": plan[:100], "error": None,
        "credits": {"used": used, "limit": limit, "remaining": remaining},
        "limits": [{"id": "monthly_credits", "label": "월 크레딧", "precision": 1,
                    "used": min(100, used / limit * 100) if limit else 100,
                    "resets_at": None,
                    "note": f"{used:,.0f} / {limit:,.0f} 크레딧 사용"}],
        "as_of": datetime.now(timezone.utc).isoformat(),
    }


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # 인증 헤더가 다른 주소로 전달되지 않게 한다.
        return None


def _fetch(key):
    request = Request(USAGE_URL, headers={"Authorization": "Bearer " + key,
                                         "Accept": "application/json"})
    with build_opener(_NoRedirect()).open(request, timeout=10) as response:
        body = response.read(1024 * 1024 + 1)
    if len(body) > 1024 * 1024:
        raise ValueError("Response too large")
    return json.loads(body)


def usage():
    result = {"limits": [], "plan": None, "credits": None, "error": None, "as_of": None}
    try:
        key = api_key()
        if not key or key.startswith("replace-"):
            result["error"] = "프로젝트 .env에 TAVILY_API_KEY를 설정하세요."
            return result
        if len(key) > 16384 or any(ord(c) <= 32 or ord(c) >= 127 for c in key):
            result["error"] = "TAVILY_API_KEY의 형식을 확인하세요."
            return result
        fingerprint = hashlib.sha256(key.encode()).hexdigest()
        with _lock:
            now = time.monotonic()
            if _cache.get("key") == fingerprint and now - _cache.get("at", 0) < CACHE_SECONDS:
                return _cache["value"]
            try:
                result = normalize(_fetch(key))
            except HTTPError as error:
                result["error"] = ("Tavily API 키 인증에 실패했습니다." if error.code in (401, 403)
                                   else "Tavily 조회 제한입니다. 잠시 뒤 다시 시도하세요." if error.code == 429
                                   else f"Tavily 사용량 API 오류: HTTP {error.code}")
            except (URLError, TimeoutError, OSError):
                result["error"] = "Tavily 사용량 API에 연결하지 못했습니다."
            except Exception:
                result["error"] = "Tavily 사용량 응답을 해석하지 못했습니다."
            _cache.update(key=fingerprint, at=time.monotonic(), value=result)
    except Exception:
        result["error"] = "Tavily 설정을 읽지 못했습니다. .env의 TAVILY_API_KEY를 확인하세요."
    return result
