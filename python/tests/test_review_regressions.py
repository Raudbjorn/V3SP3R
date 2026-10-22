import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from vesper_terminal.ai.openrouter_client import OpenRouterClient
from vesper_terminal.ui_cli.app import build_agent
from vesper_terminal.transport.mock_momentum import MockMomentumTransport

class ReviewRegressionTest(unittest.TestCase):
    def test_json_repair_preserves_quoted_content(self):
        client = OpenRouterClient()
        content = 'keep,}exact,] \\"quoted\\" ```json\n{}\n```'
        raw = json.dumps({'action': 'write_file', 'args': {'path': '/ext/a', 'content': content}})
        for text in [raw, raw[:-1] + ',}', '```json\n' + raw + '\n```', 'Output: ' + raw[:-1] + ',}']:
            with self.subTest(text=text):
                self.assertEqual(client._parse_command_json(text).args.content, content)

    def test_recursive_ancestors_and_sensitive_descendants_are_blocked(self):
        with tempfile.TemporaryDirectory() as root:
            agent, _ = build_agent(root)
            transport = agent.executor.transport
            transport.write_file('/ext/update/firmware.bin', 'firmware')
            transport.write_file('/ext/private/a.secret', 'secret')
            for command in ['rm -r /ext', 'mv /ext /backup', 'cp /ext/private /ext/copy', 'rm -r /ext/private', 'mv /ext/private /ext/moved']:
                with self.subTest(command=command):
                    text, pending = agent.send_message(command)
                    self.assertIsNone(pending)
                    self.assertIn('Blocked', text)
            self.assertEqual(transport.read_file('/ext/update/firmware.bin'), 'firmware')
            self.assertEqual(transport.read_file('/ext/private/a.secret'), 'secret')

    def test_approval_rechecks_new_protected_descendants(self):
        with tempfile.TemporaryDirectory() as root:
            agent, _ = build_agent(root)
            transport = agent.executor.transport
            transport.create_directory('/ext/demo')
            _, pending = agent.send_message('rm -r /ext/demo')
            self.assertIsNotNone(pending)
            transport.write_file('/ext/demo/a.secret', 'secret')
            self.assertFalse(agent.executor.approve(pending, agent.session_id).success)
            self.assertEqual(transport.read_file('/ext/demo/a.secret'), 'secret')

    def test_noncanonical_reads_do_not_expose_protected_content(self):
        with tempfile.TemporaryDirectory() as root:
            agent, _ = build_agent(root)
            agent.executor.transport.write_file('/int/manifest.txt', 'protected-content')
            agent.executor.transport.write_file('/ext/update/firmware.bin', 'protected-content')
            for path in ['//int/manifest.txt', '/ext//update/firmware.bin']:
                text, pending = agent.send_message('cat ' + path)
                self.assertIn('Blocked', text)
                self.assertNotIn('protected-content', text)
                self.assertIsNone(pending)

    def test_relative_data_root_directory_listing(self):
        with tempfile.TemporaryDirectory() as root:
            with patch('os.getcwd', return_value=root):
                transport = MockMomentumTransport('state')
            transport.create_directory('/ext/demo')
            self.assertEqual(transport.list_directory('/ext')[0]['path'], '/ext/demo')

    def test_copy_and_move_into_descendants_have_no_output(self):
        with tempfile.TemporaryDirectory() as root:
            transport = MockMomentumTransport(root)
            transport.write_file('/ext/backups/marker.txt', 'marker')
            for method in [transport.copy, transport.move]:
                with self.assertRaises(ValueError):
                    method('/ext', '/ext/backups/archive')
                self.assertFalse((Path(root) / 'ext/backups/archive').exists())
            self.assertEqual(transport.read_file('/ext/backups/marker.txt'), 'marker')
