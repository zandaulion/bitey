import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { generateKeyPairSync } from 'node:crypto';

const functionsRequire = createRequire(new URL('../functions/package.json', import.meta.url));
process.env.GCLOUD_PROJECT = 'demo-bitey-sdk';
process.env.FIREBASE_CONFIG = JSON.stringify({ projectId: 'demo-bitey-sdk' });
delete process.env.APPCHECK_ENFORCE;

test('real Firebase HTTP wrapper rejects requests before analysis or billing', async (t) => {
  const express = functionsRequire('express');
  const { analyse } = await import('../functions/index.js');
  const app = express();
  app.use(express.json());
  app.all('/', analyse);
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  const url = `http://127.0.0.1:${server.address().port}/`;
  try {
    await t.test('GET is refused', async () => {
      const response = await fetch(url);
      assert.equal(response.status, 405);
      assert.equal((await response.json()).error, 'method');
    });
    await t.test('POST without App Check is refused', async () => {
      const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
      assert.equal(response.status, 401);
      assert.equal((await response.json()).error, 'no_app_check');
    });
    await t.test('POST with malformed App Check is refused', async () => {
      const response = await fetch(url, {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Firebase-AppCheck': 'synthetic-invalid-token' }, body: '{}'
      });
      assert.equal(response.status, 401);
      assert.equal((await response.json()).error, 'bad_app_check');
    });
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
});

test('updated Google Auth preserves the access-token response used by Play verification', async () => {
  const { GoogleAuth } = functionsRequire('google-auth-library');
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048, privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
  const auth = new GoogleAuth({
    projectId: 'demo-bitey-sdk',
    credentials: { client_email: 'synthetic@example.invalid', private_key: privateKey },
    scopes: ['https://www.googleapis.com/auth/androidpublisher']
  });
  const client = await auth.getClient();
  client.credentials = { access_token: 'synthetic-cached-token', expiry_date: Date.now() + 3_600_000 };
  assert.equal((await client.getAccessToken()).token, 'synthetic-cached-token');
});
