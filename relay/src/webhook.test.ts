import test from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import {
  computeLinkingNonce,
  maskEmail,
  timingSafeEquals,
} from './accountLink.js'
import {
  normalizeWebhookPayload,
  RequestIdCache,
  verifyHmacSignature,
} from './webhook.js'

test('verifyHmacSignature validates bare hex and sha256= prefixed digests', () => {
  const secret = 'test-hmac-secret'
  const raw = Buffer.from('{"data":{"id":"1","type":"button_press"}}', 'utf8')
  const sig = crypto.createHmac('sha256', secret).update(raw).digest('hex')

  assert.equal(verifyHmacSignature(secret, raw, sig), true)
  assert.equal(verifyHmacSignature(secret, raw, `sha256=${sig}`), true)
  assert.equal(verifyHmacSignature(secret, raw, 'sha256=' + '0'.repeat(64)), false)
  assert.equal(verifyHmacSignature(secret, raw, undefined), false)
  assert.equal(verifyHmacSignature(secret, raw, 'not-hex'), false)
})

test('normalizeWebhookPayload parses official Ring JSON:API webhook format', () => {
  const rxMs = 1760000000500
  const payload = {
    meta: {
      version: '1.0',
      time: '2026-10-09T12:00:00.000Z',
      request_id: 'req-abc-123',
    },
    data: {
      id: 'evt-42',
      type: 'button_press',
      attributes: {
        source: 'ava1.ring.device.doorbell1',
        source_type: 'doorbell',
        timestamp: 1760000000000,
        thumbnail_url: 'https://example.invalid/thumb.jpg',
      },
    },
  }

  const evt = normalizeWebhookPayload(payload, rxMs)
  assert.equal(evt.event_id, 'evt-42')
  assert.equal(evt.event_type, 'button_press')
  assert.equal(evt.device_id, 'ava1.ring.device.doorbell1')
  assert.equal(evt.ring_event_ts_ms, 1760000000000)
  assert.equal(evt.relay_rx_ts_ms, rxMs)
  assert.equal(evt.request_id, 'req-abc-123')
})

test('RequestIdCache deduplicates repeated webhook request_ids', () => {
  const cache = new RequestIdCache(2)
  assert.equal(cache.isDuplicate('a'), false)
  assert.equal(cache.isDuplicate('a'), true)
  assert.equal(cache.isDuplicate('b'), false)
  assert.equal(cache.isDuplicate('c'), false) // evicts 'a'
  assert.equal(cache.isDuplicate('a'), false)
})

test('computeLinkingNonce produces URL-safe unpadded base64 HMAC-SHA256', () => {
  const key = 'secret-hmac-key'
  const timeMs = '1771130906289'
  const accountId = 'ava1.ring.account.TEST123'
  const expected = crypto
    .createHmac('sha256', Buffer.from(key, 'utf8'))
    .update(`${timeMs}:${accountId}`, 'utf8')
    .digest('base64url')

  const actual = computeLinkingNonce(key, timeMs, accountId)
  assert.equal(actual, expected)
  assert.equal(actual.includes('='), false)
  assert.equal(timingSafeEquals(actual, expected), true)
  assert.equal(timingSafeEquals(actual, expected + 'x'), false)
})

test('maskEmail obfuscates email local part for Ring account_identifier', () => {
  assert.equal(maskEmail('alex@example.com'), 'a***x@example.com')
  assert.equal(maskEmail('al@example.com'), 'a***@example.com')
})
