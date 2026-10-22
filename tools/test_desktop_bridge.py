import os
import pty
import socket
import struct
import threading
import unittest
from unittest.mock import patch, MagicMock
from types import SimpleNamespace

import flipper_desktop_bridge as bridge


class BridgeTests(unittest.TestCase):
    def test_fragmented_and_coalesced_frames(self):
        buffer = bytearray()
        received = []
        for byte in b''.join(map(bridge.encode_frame, [b'', b'abc', bytes(range(256))])):
            buffer.append(byte)
            received.extend(bridge.pop_frames(buffer))
        self.assertEqual(received, [b'', b'abc', bytes(range(256))])
        self.assertEqual(buffer, b'')

    def test_oversize_and_nonlocal_rejected(self):
        with self.assertRaises(ValueError):
            bridge.pop_frames(bytearray(struct.pack('>I', bridge.MAX_FRAME + 1)))
        with self.assertRaises(ValueError):
            bridge.run_bridge('/unused', '0.0.0.0', 8765, 230400)

    def test_serial_closed_when_configuration_fails(self):
        with patch.object(bridge.os, 'open', return_value=99), patch.object(bridge.os, 'close') as close:
            with patch.object(bridge, 'configure_serial', side_effect=OSError('bad serial')):
                with self.assertRaises(OSError):
                    bridge.open_serial('/unused', 230400)
            close.assert_called_once_with(99)

    def test_partial_deadline_resets_for_each_completed_frame(self):
        frame = bridge.encode_frame(b"data")
        client = MagicMock()
        client.recv.side_effect = [frame[:2]] + [frame[2:] + frame[:2]] * 7 + [b""]
        clock = [0]
        selector = MagicMock()
        def ready(**kwargs):
            clock[0] += 1
            return [(SimpleNamespace(data="client"), None)]
        selector.select.side_effect = ready
        with patch.object(bridge.selectors, 'DefaultSelector') as factory, patch.object(bridge.time, 'monotonic', side_effect=lambda: clock[0]), patch.object(bridge, 'write_all_fd') as write:
            factory.return_value.__enter__.return_value = selector
            bridge.pump(99, client)
            self.assertEqual(write.call_count, 7)

    def test_stalled_partial_frame_still_times_out(self):
        client = MagicMock()
        client.recv.return_value = b"\x00"
        clock = [0]
        selector = MagicMock()
        def ready(**kwargs):
            clock[0] += 6
            return [(SimpleNamespace(data="client"), None)] if clock[0] == 6 else []
        selector.select.side_effect = ready
        with patch.object(bridge.selectors, 'DefaultSelector') as factory, patch.object(bridge.time, 'monotonic', side_effect=lambda: clock[0]):
            factory.return_value.__enter__.return_value = selector
            with self.assertRaises(TimeoutError):
                bridge.pump(99, client)

    def test_authentication_precedes_serial_open_and_session_closes_fd(self):
        token = b'x' * 32
        client = MagicMock()
        client.recv.return_value = b'y' * 32
        with patch.object(bridge, 'open_serial') as opened:
            with self.assertRaises(PermissionError):
                bridge.serve_client(client, '/unused', 230400, token)
            opened.assert_not_called()
        client.recv.side_effect = [token[:5], token[5:]]
        with patch.object(bridge, 'open_serial', return_value=99), patch.object(bridge, 'pump'), patch.object(bridge.os, 'close') as close:
            bridge.serve_client(client, '/unused', 230400, token)
            client.sendall.assert_called_once_with(b'\x01')
            close.assert_called_once_with(99)
        client.recv.side_effect = [token]
        with patch.object(bridge, 'open_serial', return_value=99), patch.object(bridge, 'pump', side_effect=OSError('disconnect')), patch.object(bridge.os, 'close') as close:
            with self.assertRaises(OSError):
                bridge.serve_client(client, '/unused', 230400, token)
            close.assert_called_once_with(99)

    def test_default_baud_is_explicit(self):
        self.assertEqual(bridge.parse_args([]).baud, 230400)
        self.assertEqual(bridge.parse_args(['--baud', '115200']).baud, 115200)

    def test_pty_socket_round_trip_and_disconnect(self):
        master, slave = pty.openpty()
        client, server = socket.socketpair()
        bridge.configure_serial(slave, 230400)
        os.set_blocking(slave, False)
        worker = threading.Thread(target=bridge.pump, args=(slave, server), daemon=True)
        worker.start()
        try:
            client.settimeout(2)
            client.sendall(bridge.encode_frame(b'from app'))
            self.assertEqual(os.read(master, 100), b'from app')
            os.write(master, b'from flipper')
            stream = client.makefile('rb')
            self.assertEqual(struct.unpack('>I', stream.read(4))[0], 12)
            self.assertEqual(stream.read(12), b'from flipper')
            stream.close()
            client.shutdown(socket.SHUT_RDWR)
            worker.join(2)
            self.assertFalse(worker.is_alive())
        finally:
            client.close(); server.close(); os.close(master); os.close(slave)


if __name__ == '__main__':
    unittest.main()
