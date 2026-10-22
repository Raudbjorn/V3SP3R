import json
import sqlite3
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

    def test_trailing_slash_cannot_read_sensitive_file(self):
        with tempfile.TemporaryDirectory() as root:
            agent, _ = build_agent(root)
            agent.executor.transport.write_file('/ext/a.secret', 'protected-content')
            text, pending = agent.send_message('cat /ext/a.secret/')
            self.assertIn('Blocked', text)
            self.assertNotIn('protected-content', text)
            self.assertIsNone(pending)

    def test_approval_preview_names_planned_action_arguments_and_cli(self):
        with tempfile.TemporaryDirectory() as root:
            agent, _ = build_agent(root)
            agent.executor.transport.create_directory('/ext/important')
            raw = json.dumps({'action': 'delete', 'args': {'path': '/ext/important', 'recursive': True}})
            def requester(*_):
                return {'choices': [{'message': {'tool_calls': [{'function': {'name': 'execute_command', 'arguments': raw}}]}}]}
            agent.client = OpenRouterClient(api_key='test-placeholder', requester=requester)
            text, pending = agent.send_message('clean up the device')
            self.assertIsNotNone(pending)
            self.assertIn('/ext/important', text)
            self.assertIn('delete', text)
            self.assertIn('"recursive": true', text)
            self.assertTrue((Path(root) / 'flipper_mock_fs/ext/important').exists())
            agent.executor.reject(pending, agent.session_id)
            text, pending = agent.send_message('cli version')
            self.assertIn('version', text)
            self.assertIn('execute_cli', text)

    def test_persistence_closes_every_connection_after_use(self):
        connect = sqlite3.connect
        connections = []
        def capture(*args, **kwargs):
            connection = connect(*args, **kwargs)
            connections.append(connection)
            return connection
        with tempfile.TemporaryDirectory() as root:
            with patch('vesper_terminal.data.persistence.sqlite3.connect', side_effect=capture):
                agent, _ = build_agent(root)
                for _ in range(5):
                    agent.send_message('ls /ext')
                self.assertTrue(agent.persistence.history(agent.session_id))
            for connection in connections:
                with self.assertRaises(sqlite3.ProgrammingError):
                    connection.execute('SELECT 1')
