import json
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import hub


class HubRestartTests(unittest.TestCase):
    def test_same_absolute_script(self):
        process = {"name": "python.exe", "cmd": f'python.exe "{Path(hub.__file__).resolve()}" --no-browser'}
        self.assertTrue(hub.owns_hub_process(process, "http://unused"))

    def test_other_project_and_inline_command_rejected(self):
        for command in ('python.exe F:\\other\\hub.py', 'python.exe -c "import hub.py"'):
            self.assertFalse(hub.owns_hub_process({"name": "python.exe", "cmd": command}, "http://unused"))

    @patch.object(hub, "build_opener")
    def test_relative_script_checks_server_identity(self, opener):
        response = opener.return_value.open.return_value.__enter__.return_value
        response.read.return_value = json.dumps({"servers": hub.SERVERS}, default=str).encode()
        process = {"name": "python.exe", "cmd": "python.exe .\\hub.py"}
        self.assertTrue(hub.owns_hub_process(process, "http://localhost"))
        response.read.return_value = b'{"servers": []}'
        self.assertFalse(hub.owns_hub_process(process, "http://localhost"))

    @patch.object(hub, "local_server")
    def test_free_port_does_not_kill(self, server):
        with patch.object(hub.subprocess, "run") as kill:
            self.assertIs(hub.create_hub_server(), server.return_value)
            kill.assert_not_called()

    @patch.object(hub, "site_url", return_value=("127.0.87.70", 80, "http://ai-monitor.local"))
    @patch.object(hub, "listeners", return_value={123: ["127.0.87.70:80"]})
    @patch.object(hub, "processes", return_value=[{"pid": 123}])
    def test_replaces_hub_without_killing_children(self, *_):
        replacement = object()
        with patch.object(hub, "local_server", side_effect=[SystemExit(), replacement]), \
             patch.object(hub, "owns_hub_process", return_value=True), \
             patch.object(hub.subprocess, "run", return_value=MagicMock(returncode=0)) as kill:
            self.assertIs(hub.create_hub_server(), replacement)
            self.assertEqual(kill.call_args.args[0], ["taskkill", "/PID", "123", "/F"])

    @patch.object(hub, "site_url", return_value=("127.0.87.70", 80, "http://ai-monitor.local"))
    @patch.object(hub, "listeners", return_value={123: ["127.0.87.70:80"]})
    @patch.object(hub, "processes", return_value=[{"pid": 123}])
    def test_unrelated_listener_not_killed(self, *_):
        with patch.object(hub, "local_server", side_effect=SystemExit()), \
             patch.object(hub, "owns_hub_process", return_value=False), \
             patch.object(hub.subprocess, "run") as kill:
            with self.assertRaises(SystemExit):
                hub.create_hub_server()
            kill.assert_not_called()


if __name__ == "__main__":
    unittest.main()
