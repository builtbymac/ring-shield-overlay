import crypto from 'node:crypto'

export interface NormalizedRingEvent {
  event_id: string
  event_type: string
  device_id: string | null
  ring_event_ts_ms: number | null
  relay_rx_ts_ms: number
  thumbnail_url: string | null
  request_id: string | null
  synthetic?: boolean
}

/**
 * Verifies Ring's HMAC-SHA256 webhook signature over the exact raw body bytes.
 * Supports both bare hex digests and `sha256=<hex>` prefixed signatures.
 */
export function verifyHmacSignature(
  signingKey: string,
  rawBody: Buffer,
  receivedHeader: string | undefined,
): boolean {
  if (!receivedHeader) return false
  const receivedHex = receivedHeader.trim().replace(/^sha256=/i, '').toLowerCase()
  if (!/^[0-9a-f]{64}$/.test(receivedHex)) return false

  const expectedHex = crypto
    .createHmac('sha256', signingKey)
    .update(rawBody)
    .digest('hex')
    .toLowerCase()

  return crypto.timingSafeEqual(
    Buffer.from(expectedHex, 'hex'),
    Buffer.from(receivedHex, 'hex'),
  )
}

function parseTimestampMs(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) {
    // Handle both seconds and milliseconds defensively
    return value < 1e12 ? Math.round(value * 1000) : Math.round(value)
  }
  if (typeof value === 'string' && value.trim()) {
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

/**
 * Normalizes both official Ring JSON:API webhook payloads (`meta` + `data`)
 * and lightweight synthetic test payloads into a single low-overhead event frame.
 */
export function normalizeWebhookPayload(
  payload: Record<string, any>,
  relayRxTsMs: number,
  defaultDeviceId?: string,
): NormalizedRingEvent {
  if (payload?.data && typeof payload.data === 'object') {
    const data = payload.data
    const attrs = data.attributes ?? {}
    const meta = payload.meta ?? {}
    return {
      event_id: String(data.id ?? `evt_${relayRxTsMs}`),
      event_type: String(data.type ?? 'unknown'),
      device_id:
        typeof attrs.source === 'string' && attrs.source
          ? attrs.source
          : (defaultDeviceId ?? null),
      ring_event_ts_ms: parseTimestampMs(attrs.timestamp ?? meta.time),
      relay_rx_ts_ms: relayRxTsMs,
      thumbnail_url: typeof attrs.thumbnail_url === 'string' ? attrs.thumbnail_url : null,
      request_id: typeof meta.request_id === 'string' ? meta.request_id : null,
    }
  }

  return {
    event_id: String(payload?.event_id ?? payload?.id ?? `evt_${relayRxTsMs}`),
    event_type: String(payload?.event_type ?? payload?.type ?? 'unknown'),
    device_id:
      typeof payload?.device_id === 'string' && payload.device_id
        ? payload.device_id
        : (defaultDeviceId ?? null),
    ring_event_ts_ms: parseTimestampMs(payload?.timestamp),
    relay_rx_ts_ms: relayRxTsMs,
    thumbnail_url: typeof payload?.thumbnail_url === 'string' ? payload.thumbnail_url : null,
    request_id: typeof payload?.request_id === 'string' ? payload.request_id : null,
    synthetic: Boolean(payload?.synthetic),
  }
}

export class RequestIdCache {
  private readonly ids = new Set<string>()
  private readonly maxEntries: number

  constructor(maxEntries = 1000) {
    this.maxEntries = maxEntries
  }

  isDuplicate(id: string | null | undefined): boolean {
    if (!id) return false
    if (this.ids.has(id)) return true
    this.ids.add(id)
    if (this.ids.size > this.maxEntries) {
      const oldest = this.ids.values().next().value
      if (oldest !== undefined) this.ids.delete(oldest)
    }
    return false
  }
}
