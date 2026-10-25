"""ElevenLabs 잔여 크레딧·보안·캐시 검사. 실제 API 호출 없음."""
import json
import unittest
from unittest.mock import patch, MagicMock
from urllib.error import HTTPError, URLError

import elevenlabs_usage as eleven


def payload(used=595, limit=10000):
    return {"tier": "free", "character_count": used, "character_limit": limit,
            "next_character_count_reset_unix": 1793491200}


class ElevenLabsTests(unittest.TestCase):
    def test_remaining_and_reset(self):
        result = eleven.normalize(payload())
        self.assertEqual(result["credits"], {"used": 595, "limit": 10000, "remaining": 9405})
        self.assertAlmostEqual(result["limits"][0]["used"], 5.95)
        self.assertEqual(result["limits"][0]["resets_at"], "2026-11-01T00:00:00+00:00")

    def test_null_reset(self):
        source = payload()
        source["next_character_count_reset_unix"] = None
        self.assertIsNone(eleven.normalize(source)["limits"][0]["resets_at"])

    def test_zero_exhausted_and_overage(self):
        for used, limit in [(0, 0), (10000, 10000), (11000, 10000)]:
            result = eleven.normalize(payload(used, limit))
            self.assertEqual(result["credits"]["remaining"], 0)
            self.assertEqual(result["limits"][0]["used"], 100)

    def test_invalid_credits(self):
        for value in (None, -1, True, "10000", float("nan"), float("inf")):
            with self.subTest(value=value), self.assertRaises(ValueError):
                eleven.normalize(payload(limit=value))

    def test_missing_key(self):
        with patch.object(eleven, "read_api_key", return_value=""), patch.object(eleven, "_fetch") as fetch:
            result = eleven.usage()
            self.assertIn("ELEVENLABS_API_KEY", result["error"])
            self.assertIsNone(result["credits"])
            fetch.assert_not_called()

    def test_cache_key_rotation_and_expiry(self):
        with patch.object(eleven, "read_api_key", return_value="first") as key, \
                patch.object(eleven, "_fetch", return_value=payload()) as fetch, \
                patch.object(eleven.time, "monotonic", return_value=100) as clock, \
                patch.dict(eleven._cache, {}, clear=True):
            eleven.usage()
            eleven.usage()
            self.assertEqual(fetch.call_count, 1)
            key.return_value = "second"
            eleven.usage()
            self.assertEqual(fetch.call_count, 2)
            clock.return_value = 161
            eleven.usage()
            self.assertEqual(fetch.call_count, 3)

    def test_error_messages_do_not_include_secrets(self):
        secret = "test-private-secret"
        for error in (HTTPError(eleven.USAGE_URL, 401, secret, {}, None),
                      HTTPError(eleven.USAGE_URL, 429, secret, {}, None), URLError(secret), ValueError(secret)):
            with self.subTest(error=type(error).__name__), \
                    patch.object(eleven, "read_api_key", return_value=secret), \
                    patch.object(eleven, "_fetch", side_effect=error), \
                    patch.dict(eleven._cache, {}, clear=True):
                result = eleven.usage()
                self.assertIsNotNone(result["error"])
                self.assertNotIn(secret, json.dumps(result))
                self.assertIsNone(result["credits"])

    def test_allowlisted_fields_only(self):
        source = payload()
        source["api_key"] = source["open_invoices"] = "private-secret"
        result = eleven.normalize(source)
        self.assertNotIn("private-secret", json.dumps(result))

    def test_read_only_endpoint_and_auth_header(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value.read.return_value = json.dumps(payload()).encode()
        with patch.object(eleven, "build_opener", return_value=opener):
            eleven._fetch("fake-secret")
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, eleven.USAGE_URL)
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(request.get_header("Xi-api-key"), "fake-secret")
        self.assertIsNone(request.data)


if __name__ == "__main__":
    unittest.main()
