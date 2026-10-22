import os
import pty
import socket
import struct
import threading
import unittest
from unittest.mock import patch

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
            bridge.run_bridge('/unused', '0.0.0.0', 8765, (230400,))

    def test_serial_closed_when_configuration_fails(self):
        with patch.object(bridge.os, 'open', return_value=99), patch.object(bridge.os, 'close') as close:
            with patch.object(bridge, 'configure_serial', side_effect=OSError('bad serial')):
                with self.assertRaises(OSError):
                    bridge.open_serial('/unused', 230400)
            close.assert_called_once_with(99)

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
