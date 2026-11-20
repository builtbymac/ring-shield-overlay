import http from 'node:http'
import { AccountLinker } from './accountLink.js'
import { loadConfig } from './config.js'
import { RingAuthManager } from './ringAuth.js'
import {
  normalizeWebhookPayload,
  RequestIdCache,
  verifyHmacSignature,
} from './webhook.js'
import { discoverDevices, startWhepSession, stopWhepSession } from './whep.js'
import { ShieldWsHub } from './wsHub.js'

const MAX_BODY_BYTES = 256 * 1024

function readRawBody(req: http.IncomingMessage): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    let total = 0
    req.on('data', (chunk: Buffer) => {
      total += chunk.length
      if (total > MAX_BODY_BYTES) {
        reject(new Error('Body too large'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

function sendJson(res: http.ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(payload),
  })
  res.end(payload)
}

function checkLanAuth(req: http.IncomingMessage, lanToken?: string): boolean {
  if (!lanToken) return true
  const auth = req.headers.authorization?.replace(/^Bearer\s+/i, '').trim()
  return auth === lanToken
}

async function main(): Promise<void> {
  const cfg = loadConfig()
  const auth = new RingAuthManager(cfg)
  const linker = new AccountLinker(cfg, auth)
  const dedupe = new RequestIdCache(1000)

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url || '/', 'http://localhost')
    const method = req.method || 'GET'

    try {
      if (method === 'GET' && url.pathname === '/health') {
        sendJson(res, 200, {
          ok: true,
          authMode: auth.mode,
          connectedShields: hub.clientCount,
          uptimeSec: Math.round(process.uptime()),
        })
        return
      }

      // Official Ring Partner Webhook Receiver (expose ONLY this path via Cloudflare Tunnel)
      if (method === 'POST' && url.pathname === '/api/webhook') {
        const relayRxTsMs = Date.now()
        const rawBody = await readRawBody(req)

        const sigHeader =
          (req.headers['x-signature'] as string | undefined) ??
          (req.headers['signature'] as string | undefined)
        const bearerHeader = req.headers.authorization

        if (cfg.hmacKey) {
          const hmacOk = verifyHmacSignature(cfg.hmacKey, rawBody, sigHeader)
          const bearerOk =
            Boolean(cfg.webhookSecret) && bearerHeader === `Bearer ${cfg.webhookSecret}`
          if (!hmacOk && !bearerOk) {
            console.warn('[webhook] Rejected request: invalid HMAC signature')
            sendJson(res, 401, { error: 'Invalid signature' })
            return
          }
        } else if (cfg.webhookSecret && bearerHeader !== `Bearer ${cfg.webhookSecret}`) {
          sendJson(res, 401, { error: 'Unauthorized' })
          return
        }

        const parsed = JSON.parse(rawBody.toString('utf8')) as Record<string, any>
        const event = normalizeWebhookPayload(parsed, relayRxTsMs, cfg.defaultDeviceId)

        if (dedupe.isDuplicate(event.request_id)) {
          sendJson(res, 200, { status: 'already_processed', request_id: event.request_id })
          return
        }

        const delivered = hub.broadcastEvent(event)
        console.log(
          `[webhook] type=${event.event_type} device=${event.device_id} delivered=${delivered} raw_keys=${Object.keys(parsed).join(',')}`,
        )
        sendJson(res, 200, { status: 'ok', event_id: event.event_id, delivered })
        return
      }

      // Ring Private App One-Way Account Linking endpoints (HTTPS via Cloudflare Tunnel)
      if (method === 'POST' && url.pathname === '/ring/token-exchange') {
        const rawBody = await readRawBody(req)
        const ct = String(req.headers['content-type'] ?? '')
        await linker.handleTokenExchange(rawBody, ct)
        sendJson(res, 200, { status: 'ok' })
        return
      }

      if (method === 'GET' && url.pathname === '/ring/link') {
        const { status, html } = linker.renderLinkPage(
          url.searchParams.get('nonce'),
          url.searchParams.get('time'),
        )
        res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8' })
        res.end(html)
        return
      }

      if (method === 'POST' && url.pathname === '/ring/complete-link') {
        const rawBody = await readRawBody(req)
        const body = JSON.parse(rawBody.toString('utf8')) as {
          nonce?: string
          time?: string
          email?: string
          passcode?: string
        }
        const result = await linker.completeLinking(body)
        sendJson(res, 200, { status: 'completed', ...result })
        return
      }

      if (method === 'GET' && url.pathname === '/ring/home') {
        const html = `<!doctype html><html><body style="font-family:system-ui;background:#0f141c;color:#e6edf3;padding:32px"><h1>Ring → Nvidia Shield Pro Relay</h1><p>Auth mode: <b>${auth.mode}</b> • Connected Shields: <b>${hub.clientCount}</b></p></body></html>`
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
        res.end(html)
        return
      }

      // LAN-only endpoints below: never expose over Cloudflare Tunnel
      if (req.headers['cf-ray'] || req.headers['cf-connecting-ip']) {
        sendJson(res, 403, { error: 'LAN-only endpoint' })
        return
      }
      if (!checkLanAuth(req, cfg.lanToken)) {
        sendJson(res, 401, { error: 'Unauthorized LAN client' })
        return
      }

      // Synthetic trigger for PoC benchmarking (before/alongside live Ring webhooks)
      if (method === 'POST' && url.pathname === '/api/test/trigger') {
        const relayRxTsMs = Date.now()
        const rawBody = await readRawBody(req)
        const body = rawBody.length ? (JSON.parse(rawBody.toString('utf8')) as Record<string, any>) : {}
        const event = normalizeWebhookPayload(
          {
            event_type: body.event_type || 'button_press',
            device_id: body.device_id || cfg.defaultDeviceId,
            timestamp: relayRxTsMs,
            synthetic: true,
          },
          relayRxTsMs,
          cfg.defaultDeviceId,
        )
        const delivered = hub.broadcastEvent(event)
        console.log(`[test] Triggered ${event.event_type} for device=${event.device_id} -> ${delivered} client(s)`)
        sendJson(res, 200, { ok: true, event, delivered })
        return
      }

      // Device discovery (GET https://api.amazonvision.com/v1/devices)
      if (method === 'GET' && url.pathname === '/api/devices') {
        const devices = await discoverDevices(auth)
        sendJson(res, 200, { devices })
        return
      }

      // WHEP Start Proxy: Shield sends SDP offer -> Relay forwards to api.amazonvision.com
      if (method === 'POST' && url.pathname === '/api/whep/start') {
        const rawBody = await readRawBody(req)
        const { sdpOffer, deviceId } = JSON.parse(rawBody.toString('utf8')) as {
          sdpOffer?: string
          deviceId?: string
        }
        const cleanedId = deviceId && deviceId !== 'null' ? deviceId : undefined
        const resolvedId = cleanedId || cfg.defaultDeviceId
        if (!sdpOffer || !resolvedId) {
          sendJson(res, 400, { error: 'Both sdpOffer and deviceId (or RING_DEVICE_ID) are required' })
          return
        }
        const result = await startWhepSession(auth, resolvedId, sdpOffer)
        console.log(`[whep] Started session for ${resolvedId} in ${result.ringRttMs} ms`)
        sendJson(res, 200, result)
        return
      }

      // WHEP Stop Proxy: cleanly releases battery doorbell stream on overlay dismiss
      if (method === 'DELETE' && url.pathname === '/api/whep/stop') {
        const rawBody = await readRawBody(req)
        const { sessionUrl } = JSON.parse(rawBody.toString('utf8')) as { sessionUrl?: string }
        if (!sessionUrl) {
          sendJson(res, 400, { error: 'Missing sessionUrl' })
          return
        }
        await stopWhepSession(auth, sessionUrl)
        console.log('[whep] Stopped session cleanly')
        sendJson(res, 200, { ok: true })
        return
      }

      sendJson(res, 404, { error: 'Not found' })
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      console.error(`[http] ${method} ${url.pathname} failed:`, msg)
      sendJson(res, 500, { error: msg })
    }
  })

  const hub = new ShieldWsHub(server, cfg.lanToken)

  await auth.warm()

  server.listen(cfg.port, cfg.host, () => {
    console.log(
      `[relay] Listening on http://${cfg.host}:${cfg.port} (authMode=${auth.mode}, defaultDevice=${cfg.defaultDeviceId ?? 'auto'})`,
    )
  })

  const shutdown = () => {
    console.log('[relay] Shutting down...')
    auth.stop()
    hub.close()
    server.close(() => process.exit(0))
  }
  process.on('SIGINT', shutdown)
  process.on('SIGTERM', shutdown)
}

main().catch((err) => {
  console.error('[relay] Fatal error:', err)
  process.exit(1)
})
