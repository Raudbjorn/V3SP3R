import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from vesper_terminal.ui_cli.app import build_agent
from vesper_terminal.ai.openrouter_client import OpenRouterClient
from vesper_terminal.data.config_store import PlaintextConfigStore
from vesper_terminal.domain.models import CommandAction, CommandArgs, ExecuteCommand
from vesper_terminal.transport.factory import create_transport
from vesper_terminal.transport.pyflipper_usb import PyFlipperUsbTransport, UnsupportedTransportError


class TerminalSafetyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.agent, self.profile = build_agent(str(self.root))

    def tearDown(self):
        self.tmp.cleanup()

    def test_local_commands_without_key_and_json_write_approval(self):
        self.assertTrue(self.profile['mock_mode'])
        text, pending = self.agent.send_message('mkdir /ext/demo')
        self.assertIsNone(pending)
        self.assertTrue((self.root/'flipper_mock_fs/ext/demo').is_dir())
        text, pending = self.agent.send_message('tool {"action":"write_file","args":{"path":"/ext/demo/a.txt","content":"hello"}}')
        self.assertIsNotNone(pending)
        self.assertFalse((self.root/'flipper_mock_fs/ext/demo/a.txt').exists())
        self.assertTrue(self.agent.executor.approve(pending, self.agent.session_id).success)
        self.assertIn('hello', self.agent.send_message('cat /ext/demo/a.txt')[0])
        self.assertIn('a.txt', self.agent.send_message('ls /ext/demo')[0])
        self.assertIsNone(self.agent.send_message('cp /ext/demo/a.txt /ext/demo/b.txt')[1])
        _, pending = self.agent.send_message('mv /ext/demo/b.txt /ext/demo/c.txt')
        self.assertIsNotNone(pending)
        self.assertTrue(self.agent.executor.approve(pending, self.agent.session_id).success)
        _, pending = self.agent.send_message('rm /ext/demo/c.txt')
        self.agent.executor.reject(pending, self.agent.session_id)
        self.assertTrue((self.root/'flipper_mock_fs/ext/demo/c.txt').exists())

    def test_cross_session_cannot_approve(self):
        _, pending = self.agent.send_message('cli version')
        self.assertFalse(self.agent.executor.approve(pending, 'other-session').success)
        self.assertTrue(self.agent.executor.approve(pending, self.agent.session_id).success)

    def test_expired_approval_does_not_execute(self):
        _, pending = self.agent.send_message('cli version')
        self.agent.executor.pending[pending].expires_at = 0
        self.assertFalse(self.agent.executor.approve(pending, self.agent.session_id).success)

    def test_paths_and_control_characters_blocked(self):
        for path in ['/ext/../int/manifest.txt', '//int/manifest.txt', '/ext//update/fw', '/int', '/ext/update', '/ext/.region', '/ext/a.secret', 'relative', '/ext/a\nb']:
            command = ExecuteCommand(CommandAction.DELETE, CommandArgs(path=path), '', '')
            result = self.agent.executor.execute(command, self.agent.session_id)
            self.assertFalse(result.success, path)
            self.assertFalse(result.requires_confirmation, path)
        _, pending = self.agent.send_message('mkdir /external/new')
        self.assertIsNotNone(pending, 'permission /ext must not cover /external')

    def test_symlink_cannot_escape_or_bypass_policy(self):
        outside = self.root/'private'
        outside.write_text('private')
        (self.root/'flipper_mock_fs/ext/link').symlink_to(outside)
        self.assertIn('Path escapes', self.agent.send_message('cat /ext/link')[0])
        internal = self.root/'flipper_mock_fs/int'
        internal.mkdir()
        (internal/'secret').write_text('private')
        (self.root/'flipper_mock_fs/ext/internal').symlink_to(internal)
        self.assertIn('Symlinks', self.agent.send_message('cat /ext/internal/secret')[0])

    def test_bad_arity_and_json_types_rejected(self):
        for text in ['cp /ext/a', 'rm -f /ext/a', 'ls /ext /int', 'tool {"action":"delete","args":{"recursive":"false"}}', 'tool {"action":"read_file","args":{"path":42}}']:
            with self.assertRaises((ValueError, TypeError)):
                self.agent.send_message(text)

    def test_usb_selection_never_falls_back(self):
        config = PlaintextConfigStore(str(self.root/'usb.env'))
        config.set('TRANSPORT_MODE', 'usb')
        config.set('ALLOW_MOCK_FALLBACK', 'true')
        with patch.object(PyFlipperUsbTransport, '_probe_capabilities', return_value={'supports_cli': False, 'transport_mode': 'USB_SERIAL'}):
            transport, profile = create_transport(config, self.root)
        self.assertIsInstance(transport, PyFlipperUsbTransport)
        self.assertFalse(profile.get('mock_mode', False))
        with self.assertRaises(UnsupportedTransportError):
            transport.write_file('/ext/a', 'x')

    def test_unknown_transport_rejected(self):
        config = PlaintextConfigStore(str(self.root/'bad.env'))
        config.set('TRANSPORT_MODE', 'typo')
        with self.assertRaises(ValueError):
            create_transport(config, self.root)

    def test_local_commands_stay_local_even_with_key(self):
        def fail(*args):
            self.fail('local command attempted a network request')
        client = OpenRouterClient(api_key='test-placeholder', requester=fail)
        self.assertEqual(client.chat('ls /ext').command.action, CommandAction.LIST_DIRECTORY)

    def test_interactive_errors_retry_and_quit_during_approval(self):
        result = subprocess.run([sys.executable, "main.py", "--data-dir", str(self.root)],
            input='{invalid}\nretry\ncp /ext/a\nretry\ncli version\nquit\n', text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.count("Invalid command:"), 4)
        self.assertIn("Type YES to approve", result.stdout)

    def test_read_missing_directory_has_no_write_side_effect(self):
        self.agent.send_message('cat /ext/does/not/exist')
        self.assertFalse((self.root/'flipper_mock_fs/ext/does').exists())


if __name__ == '__main__':
    unittest.main()
