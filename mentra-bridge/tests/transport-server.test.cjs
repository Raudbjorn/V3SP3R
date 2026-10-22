const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createRelayServer } = require('../dist/transportServer');
const { execFileSync } = require('node:child_process');
const { mkdtempSync, readFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const https = require('node:https');

test('requested TLS never falls back to plaintext', () => {
  assert.throws(() => createRelayServer({ USE_TLS: 'true' }), /requires/);
  assert.throws(() => createRelayServer({ USE_TLS: 'true', SSL_CERT: 'missing', SSL_KEY: 'missing' }));
});

test('relay accepts a verified TLS connection', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'vesper-tls-'));
  const cert = join(directory, 'cert.pem');
  const key = join(directory, 'key.pem');
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', key,
    '-out', cert, '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'], {stdio: 'ignore'});
  const server = createRelayServer({ USE_TLS: 'true', SSL_CERT: cert, SSL_KEY: key });
  server.on('request', (_request, response) => response.end('secure'));
  try {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const result = await new Promise((resolve, reject) => {
      https.get({hostname: '127.0.0.1', servername: 'localhost', port: server.address().port,
        ca: readFileSync(cert)}, response => {
        let body = ''; response.on('data', chunk => body += chunk);
        response.on('end', () => resolve(body));
      }).on('error', reject);
    });
    assert.equal(result, 'secure');
  } finally {
    await new Promise(resolve => server.close(resolve));
    rmSync(directory, {recursive: true});
  }
});
