from __future__ import annotations

import time
from dataclasses import dataclass

from .base import FlipperTransport


class UnsupportedTransportError(RuntimeError):
    """Raised when USB transport is unavailable on the current host/runtime."""


@dataclass
class UsbTransportConfig:
    device_path: str
    baud_rate: int = 230400
    read_timeout_s: float = 0.8


class PyFlipperUsbTransport(FlipperTransport):
    """Momentum-first USB transport scaffold.

    This implementation is intentionally conservative and provides graceful
    unsupported behavior when USB serial dependencies or a target device are
    unavailable. It is designed to be swapped in incrementally as command-level
    compatibility is validated against real Momentum firmware.
    """

    MAX_IDLE_ROUNDS = 2

    def __init__(self, config: UsbTransportConfig) -> None:
        self.config = config
        self._serial = None
        self._serial_module = None
        self._profile = self._probe_capabilities()

    def _probe_capabilities(self) -> dict:
        profile = {
            "firmware_family": "UNKNOWN",
            "transport_mode": "USB_SERIAL",
            "supports_cli": False,
            "supports_rpc": False,
            "supports_rpc_app_bridge": False,
            "connected": False,
            "unsupported_reason": "",
        }
        try:
            import serial  # type: ignore

            self._serial_module = serial
        except ImportError:
            profile["unsupported_reason"] = (
                "pyserial is not installed; install it to enable USB transport"
            )
            return profile

        try:
            with self._serial_module.Serial(
                self.config.device_path,
                self.config.baud_rate,
                timeout=self.config.read_timeout_s,
                write_timeout=5,
            ):
                pass
            profile["supports_cli"] = True
            profile["connected"] = True
            return profile
        except (
            getattr(self._serial_module, "SerialException", OSError),
            OSError,
            ValueError,
        ) as exc:
            profile["unsupported_reason"] = str(exc)
            return profile

    def capability_profile(self) -> dict:
        return dict(self._profile)

    def _ensure_connected(self) -> None:
        if not self._profile.get("supports_cli"):
            raise UnsupportedTransportError(
                self._profile.get("unsupported_reason") or "USB CLI transport unavailable"
            )
        if self._serial is None:
            self._serial = self._serial_module.Serial(
                self.config.device_path,
                self.config.baud_rate,
                timeout=self.config.read_timeout_s,
                write_timeout=5,
            )
            time.sleep(0.05)

    def execute_cli(self, command: str) -> str:
        if not command.strip() or any(ord(c) < 32 for c in command) or len(command) > 4096:
            raise ValueError("Expected one CLI command of at most 4096 characters")
        self._ensure_connected()
        if self._serial is None:
            raise UnsupportedTransportError("USB CLI transport is not connected")
        cmd = command.strip() + "\r\n"
        self._serial.write(cmd.encode("utf-8"))
        self._serial.flush()

        lines: list[str] = []
        idle_rounds = 0
        deadline = time.monotonic() + 10
        total_bytes = 0
        while idle_rounds < self.MAX_IDLE_ROUNDS:
            if time.monotonic() > deadline or total_bytes > 1_048_576:
                raise TimeoutError("USB response exceeded time or size limit")
            raw = self._serial.readline(65536)
            if not raw:
                idle_rounds += 1
                continue
            idle_rounds = 0
            total_bytes += len(raw)
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
            if line:
                lines.append(line)
        return "[Unverified USB CLI response]\n" + "\n".join(lines)

    def close(self) -> None:
        if self._serial is not None:
            self._serial.close()
            self._serial = None

    def _unsupported_storage(self):
        raise UnsupportedTransportError("Structured USB storage/RPC is not implemented; use explicit cli commands or select mock mode")

    def list_directory(self, path): return self._unsupported_storage()
    def read_file(self, path): return self._unsupported_storage()
    def write_file(self, path, content): return self._unsupported_storage()
    def create_directory(self, path): return self._unsupported_storage()
    def delete(self, path, recursive=False): return self._unsupported_storage()
    def move(self, src, dest): return self._unsupported_storage()
    def copy(self, src, dest): return self._unsupported_storage()
    def get_device_info(self): return self._unsupported_storage()
    def get_storage_info(self): return self._unsupported_storage()
