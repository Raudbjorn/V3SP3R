# V3SP3R Python terminal

A terminal client for development, with explicit mock filesystem mode and an experimental USB CLI adapter. Python 3.10+; local commands and tests use only the standard library.

```sh
cd python
python3 main.py
python3 -m unittest discover -s tests -v
```

Use `--data-dir /path/to/private-directory` to isolate config, SQLite chat/audit history, and `flipper_mock_fs`. Default: `~/.vesper_terminal`. Startup always names the active transport. **Mock mode affects only files in that directory; it does not control hardware.**

No API key is required for interactive commands or direct JSON:

```text
mkdir /ext/demo
ls /ext
{"action":"write_file","args":{"path":"/ext/demo/a.txt","content":"hello"}}
YES
cat /ext/demo/a.txt
cp /ext/demo/a.txt /ext/demo/b.txt
mv /ext/demo/b.txt /ext/demo/c.txt
YES
rm /ext/demo/c.txt
no
cli storage info
YES
```

`tool { ... }` is also accepted. `history`, `new`, `retry`, and `quit` control the interactive session. Mutations and arbitrary CLI commands retain risk assessment and approval; approval expires after two minutes and belongs to its originating session. `no` or `reject` denies it. Paths must be absolute, without traversal or control characters. Internal/firmware paths and sensitive suffixes remain blocked; symlinks cannot escape the mock tree. Unsupported donor features (resource downloads, forge, runbooks, app installation, transmission) are not implemented by this terminal and return an error.

Local commands remain local even when an optional `OPENROUTER_API_KEY` is set in `config.env`. Other text can use the donor OpenRouter client; provider requests and hardware were not exercised during validation. Protect the config and data directory: config may hold a credential and history/audit records contain command arguments and file content.

## Real USB limitations

Set `TRANSPORT_MODE=usb` and `USB_DEVICE_PATH=/dev/ttyACM0` in the selected data directory's `config.env`; optional `USB_BAUD_RATE=230400`. Install the optional adapter dependency with `python3 -m pip install -r requirements-usb.txt` in a virtual environment.

Selecting USB **never falls back to mock**, including when the legacy `ALLOW_MOCK_FALLBACK=true` setting exists. Connection or dependency failures remain visible. Unknown transport names are rejected. Raw `cli` is experimental and bounded by response size/time; output is labeled unverified. The adapter does not implement protobuf RPC or verified structured storage operations (`ls`, `cat`, writes, moves, etc.) against real hardware; those fail explicitly. Do not interpret a CLI response as hardware acceptance. Physical USB testing is still required.

## Source

Adapted from [gattaa/V3SP3R_Docker](https://github.com/gattaa/V3SP3R_Docker/tree/be79ca73d69499535d4915c340dc3f42db4f5960/python), pinned commit `be79ca73d69499535d4915c340dc3f42db4f5960`, under AGPL-3.0. This port narrows execution to terminal/file commands, removes silent mock fallback and unrelated mock feature claims, and hardens paths, approval sessions, input handling, and USB bounds.
