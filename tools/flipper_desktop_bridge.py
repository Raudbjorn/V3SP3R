#!/usr/bin/env python3
"""Local TCP-to-Flipper USB bridge for Android emulator debug builds."""

from __future__ import annotations

import argparse
import ipaddress
import os
import selectors
import socket
import struct
import sys
import termios
import time
from pathlib import Path


DEFAULT_SERIAL = Path("/dev/ttyACM0")
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8765
BAUD_CANDIDATES = (230400, 115200, 460800, 921600)
RPC_PRIME_COMMANDS = (b"start_rpc_session\r", b"start_rpc_session\r\n", b"\n")
MAX_FRAME = 1024 * 1024


def encode_frame(payload: bytes) -> bytes:
    if len(payload) > MAX_FRAME:
        raise ValueError(f"frame too large: {len(payload)}")
    return struct.pack(">I", len(payload)) + payload


def pop_frames(buffer: bytearray) -> list[bytes]:
    frames: list[bytes] = []
    while len(buffer) >= 4:
        size = struct.unpack(">I", buffer[:4])[0]
        if size > MAX_FRAME:
            raise ValueError(f"frame too large: {size}")
        if len(buffer) < 4 + size:
            break
        frames.append(bytes(buffer[4 : 4 + size]))
        del buffer[: 4 + size]
    return frames


def baud_constant(baud: int) -> int:
    name = f"B{baud}"
    if not hasattr(termios, name):
        raise ValueError(f"unsupported baud rate by this platform: {baud}")
    return getattr(termios, name)


def configure_serial(fd: int, baud: int) -> None:
    attrs = termios.tcgetattr(fd)
    attrs[0] = 0
    attrs[1] = 0
    attrs[2] = termios.CLOCAL | termios.CREAD | termios.CS8
    attrs[3] = 0
    attrs[4] = baud_constant(baud)
    attrs[5] = baud_constant(baud)
    attrs[6][termios.VMIN] = 0
    attrs[6][termios.VTIME] = 1
    termios.tcsetattr(fd, termios.TCSANOW, attrs)


def resolve_serial_path(requested: str | None) -> Path:
    if requested:
        return Path(requested)
    return DEFAULT_SERIAL


def open_serial(path: Path, baud: int) -> int:
    fd = os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    try:
        configure_serial(fd, baud)
        # Enter RPC once; sending further CLI commands would corrupt that stream.
        write_all_fd(fd, RPC_PRIME_COMMANDS[0])
        time.sleep(0.1)
        termios.tcflush(fd, termios.TCIFLUSH)
        return fd
    except BaseException:
        os.close(fd)
        raise


def write_all_fd(fd: int, data: bytes) -> None:
    view = memoryview(data)
    deadline = time.monotonic() + 5
    while view:
        if time.monotonic() > deadline:
            raise TimeoutError("serial write timed out")
        try:
            written = os.write(fd, view)
        except BlockingIOError:
            time.sleep(0.01)
            continue
        if written == 0:
            raise OSError("serial write returned 0 bytes")
        view = view[written:]


def run_bridge(serial_path: Path, host: str, port: int, baud_candidates: tuple[int, ...]) -> None:
    if not ipaddress.ip_address(host).is_loopback:
        raise ValueError("Development bridge only binds a loopback IP")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((host, port))
        server.listen(1)
        print(f"listening on {host}:{port}", flush=True)
        while True:
            client, addr = server.accept()
            with client:
                # Re-open/re-prime for each connection; do not reuse stale RPC bytes.
                serial_fd = None
                try:
                    serial_fd = open_serial(serial_path, baud_candidates[0])
                    client.settimeout(5)
                    pump(serial_fd, client)
                except (OSError, ValueError) as exc:
                    print(f"bridge session failed: {exc}", file=sys.stderr)
                finally:
                    if serial_fd is not None:
                        os.close(serial_fd)


def pump(serial_fd: int, client: socket.socket) -> None:
    with selectors.DefaultSelector() as selector:
        selector.register(client, selectors.EVENT_READ, "client")
        selector.register(serial_fd, selectors.EVENT_READ, "serial")
        client_buffer = bytearray()
        partial_since = None
        while True:
            if partial_since is not None and time.monotonic() - partial_since > 5:
                raise TimeoutError("incomplete frame timed out")
            for key, _ in selector.select(timeout=0.25):
                if key.data == "client":
                    chunk = client.recv(65536)
                    if not chunk:
                        return
                    client_buffer.extend(chunk)
                    for frame in pop_frames(client_buffer):
                        if frame:
                            write_all_fd(serial_fd, frame)
                    if client_buffer:
                        if partial_since is None:
                            partial_since = time.monotonic()
                    else:
                        partial_since = None
                else:
                    try:
                        data = os.read(serial_fd, 4096)
                    except BlockingIOError:
                        continue
                    if not data:
                        return
                    client.sendall(encode_frame(data))


def self_test() -> None:
    payloads = [b"", b"abc", bytes(range(256))]
    stream = bytearray().join(encode_frame(payload) for payload in payloads)
    partial = bytearray(stream[:7])
    assert pop_frames(partial) == [b""]
    partial.extend(stream[7:])
    assert pop_frames(partial) == payloads[1:]
    assert partial == bytearray()
    try:
        pop_frames(bytearray(struct.pack(">I", MAX_FRAME + 1)))
    except ValueError:
        pass
    else:
        raise AssertionError("oversized frame was accepted")
    read_fd, write_fd = os.pipe()
    try:
        os.set_blocking(write_fd, False)
        write_all_fd(write_fd, b"serial")
        assert os.read(read_fd, 6) == b"serial"
    finally:
        os.close(read_fd)
        os.close(write_fd)
    print("self-test ok")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="serial device path")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--baud", type=int, choices=BAUD_CANDIDATES, help="single baud rate to try")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.host != "127.0.0.1":
        parser.error("only 127.0.0.1 is supported; use the emulator host alias 10.0.2.2")
    if not 1 <= args.port <= 65535:
        parser.error("port must be 1..65535")
    return args


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        self_test()
        return 0
    baud_candidates = (args.baud,) if args.baud else BAUD_CANDIDATES
    run_bridge(resolve_serial_path(args.serial), args.host, args.port, baud_candidates)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
