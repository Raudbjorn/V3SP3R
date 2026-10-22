# Relay TLS

Set `USE_TLS=true`, `SSL_CERT=/path/fullchain.pem`, and `SSL_KEY=/path/private-key.pem` to serve the relay over `wss://`. The server refuses to start if TLS is requested without usable credentials. TLS 1.2 or newer is required. Use a certificate trusted by Android, or terminate TLS at your existing trusted reverse proxy.

The Android release app accepts only `wss://` bridge connections. Debug builds additionally accept explicit `ws://localhost`, `ws://127.0.0.1`, and `ws://10.0.2.2` development bridges. For USB-connected local development, run `adb reverse tcp:8089 tcp:8089` and configure the debug app with `ws://127.0.0.1:8089`. A LAN address needs TLS. The separate HTTP health endpoint carries no conversation payloads.

Run `npm run build && node --test tests/transport-server.test.cjs` to verify fail-closed configuration and a trusted TLS handshake. The handshake test uses OpenSSL to create a temporary test certificate.
