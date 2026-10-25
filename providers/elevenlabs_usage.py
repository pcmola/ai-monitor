"""ElevenLabs 구독 잔여 크레딧 조회. 음성 생성·결제 요청은 하지 않는다."""

import hashlib
import json
import threading
import time
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener

from providers.tavily_usage import _NoRedirect, _number, read_api_key

USAGE_URL = "https://api.elevenlabs.io/v1/user/subscription"
CACHE_SECONDS = 60
_cache = {}
_lock = threading.Lock()


def normalize(payload):
    used = _number(payload["character_count"])
    limit = _number(payload["character_limit"])
    plan = payload["tier"]
    if not isinstance(plan, str):
        raise ValueError("Invalid subscription tier")
    reset = payload.get("next_character_count_reset_unix")
    resets_at = None
    if reset is not None:
        resets_at = datetime.fromtimestamp(_number(reset), timezone.utc).isoformat()
    return {
        "plan": plan[:100], "error": None,
        "credits": {"used": used, "limit": limit, "remaining": max(0, limit - used)},
        "limits": [{"id": "subscription_credits", "label": "구독 크레딧", "precision": 2,
                    "used": min(100, used / limit * 100) if limit else 100,
                    "resets_at": resets_at,
                    "note": f"{used:,.0f} / {limit:,.0f} 크레딧 사용"}],
        "as_of": datetime.now(timezone.utc).isoformat(),
    }


def _fetch(key):
    request = Request(USAGE_URL, headers={"xi-api-key": key, "Accept": "application/json"})
    with build_opener(_NoRedirect()).open(request, timeout=10) as response:
        body = response.read(1024 * 1024 + 1)
    if len(body) > 1024 * 1024:
        raise ValueError("Response too large")
    return json.loads(body)


def usage():
    result = {"limits": [], "plan": None, "credits": None, "error": None, "as_of": None}
    try:
        key = read_api_key("ELEVENLABS_API_KEY")
        if not key or key.startswith("replace-"):
            result["error"] = "프로젝트 .env에 ELEVENLABS_API_KEY를 설정하세요."
            return result
        if len(key) > 16384 or any(ord(c) <= 32 or ord(c) >= 127 for c in key):
            result["error"] = "ELEVENLABS_API_KEY의 형식을 확인하세요."
            return result
        fingerprint = hashlib.sha256(key.encode()).hexdigest()
        with _lock:
            if _cache.get("key") == fingerprint and time.monotonic() - _cache.get("at", 0) < CACHE_SECONDS:
                return _cache["value"]
            try:
                result = normalize(_fetch(key))
            except HTTPError as error:
                result["error"] = ("ElevenLabs API 키와 사용자 조회(user_read) 권한을 확인하세요." if error.code in (401, 403)
                                   else "ElevenLabs 조회 제한입니다. 잠시 뒤 다시 시도하세요." if error.code == 429
                                   else f"ElevenLabs 사용량 API 오류: HTTP {error.code}")
            except (URLError, TimeoutError, OSError):
                result["error"] = "ElevenLabs 사용량 API에 연결하지 못했습니다."
            except Exception:
                result["error"] = "ElevenLabs 사용량 응답을 해석하지 못했습니다."
            _cache.update(key=fingerprint, at=time.monotonic(), value=result)
    except Exception:
        result["error"] = "ElevenLabs 설정을 읽지 못했습니다. .env의 ELEVENLABS_API_KEY를 확인하세요."
    return result
