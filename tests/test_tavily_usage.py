"""Tavily 계정 잔여량·캐시·키 비노출 검사 (실제 API 호출 없음)."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError, URLError

from providers import tavily_usage as tavily


def payload(used=28, limit=1000):
    return {"account": {"current_plan": "Researcher", "plan_usage": used, "plan_limit": limit},
            "key": {"usage": 10, "limit": 100}}


class TavilyTests(unittest.TestCase):
    def test_account_not_key_quota(self):
        result = tavily.normalize(payload())
        self.assertEqual(result["credits"], {"used": 28, "limit": 1000, "remaining": 972})
        self.assertAlmostEqual(result["limits"][0]["used"], 2.8)

    def test_exhausted_zero_and_overage(self):
        for used, limit in [(1000, 1000), (1200, 1000), (0, 0)]:
            result = tavily.normalize(payload(used, limit))
            self.assertEqual(result["credits"]["remaining"], 0)
            self.assertEqual(result["limits"][0]["used"], 100)

    def test_invalid_counts(self):
        for value in (-1, float("nan"), float("inf"), True, None, "1000"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                tavily.normalize(payload(limit=value))

    def test_missing_key_does_not_fetch(self):
        with patch.object(tavily, "api_key", return_value=""), patch.object(tavily, "_fetch") as fetch:
            result = tavily.usage()
            self.assertIsNotNone(result["error"])
            self.assertIsNone(result["credits"])
            fetch.assert_not_called()

    def test_cache_and_key_rotation(self):
        with patch.object(tavily, "api_key", return_value="test-secret") as key, \
                patch.object(tavily, "_fetch", return_value=payload()) as fetch, \
                patch.dict(tavily._cache, {}, clear=True):
            self.assertEqual(tavily.usage()["credits"]["remaining"], 972)
            tavily.usage()
            self.assertEqual(fetch.call_count, 1)
            key.return_value = "replacement-secret"
            tavily.usage()
            self.assertEqual(fetch.call_count, 2)

    def test_errors_never_disclose_key_or_response(self):
        secret = "test-private-secret"
        for error in (HTTPError(tavily.USAGE_URL, 401, secret, {}, None),
                      HTTPError(tavily.USAGE_URL, 429, secret, {}, None),
                      URLError(secret), ValueError(secret)):
            with self.subTest(error=type(error).__name__), \
                    patch.object(tavily, "api_key", return_value=secret), \
                    patch.object(tavily, "_fetch", side_effect=error), \
                    patch.dict(tavily._cache, {}, clear=True):
                result = tavily.usage()
                self.assertIsNotNone(result["error"])
                self.assertNotIn(secret, json.dumps(result))
                self.assertIsNone(result["credits"])

    def test_only_allowlisted_fields_are_returned(self):
        source = payload()
        source["api_key"] = source["account"]["secret"] = "private-secret"
        self.assertNotIn("private-secret", json.dumps(tavily.normalize(source)))

    def test_env_file_and_environment_precedence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            # 텍스트는 가짜 키이며 파일 생성도 테스트가 관리한다.
            with path.open("w", encoding="utf-8") as output:
                output.write('OTHER=value\nexport TAVILY_API_KEY="test-file-key" # note\n')
            with patch.object(tavily, "ENV_PATH", path), patch.dict(tavily.os.environ, {}, clear=True):
                self.assertEqual(tavily.api_key(), "test-file-key")
                tavily.os.environ["TAVILY_API_KEY"] = "test-env-key"
                self.assertEqual(tavily.api_key(), "test-env-key")

    def test_redirect_is_not_followed(self):
        self.assertIsNone(tavily._NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.invalid"))


if __name__ == "__main__":
    unittest.main()
