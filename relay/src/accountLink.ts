import crypto from 'node:crypto'
import { fetch as undiciFetch } from 'undici'
import type { RelayConfig } from './config.js'
import { RING_API_BASE, RingAuthManager, upstreamAgent } from './ringAuth.js'

const RING_OAUTH_URL = 'https://oauth.ring.com/oauth/token'
const UNCLAIMED_TTL_MS = 15 * 60 * 1000

export interface UnclaimedTokenRecord {
  accessToken: string
  refreshToken: string
  expiresIn: number
  accountId: string
  createdAtMs: number
}

/**
 * Computes Ring's one-way account-linking nonce:
 *   HMAC-SHA256(hmacKey, "<timestamp_ms>:<account_id>") -> URL-safe Base64 without padding
 */
export function computeLinkingNonce(hmacKey: string, timeMs: string, accountId: string): string {
  const payload = `${timeMs}:${accountId}`
  return crypto
    .createHmac('sha256', Buffer.from(hmacKey, 'utf8'))
    .update(payload, 'utf8')
    .digest('base64url')
}

export function timingSafeEquals(a: string, b: string): boolean {
  const bufA = Buffer.from(a, 'utf8')
  const bufB = Buffer.from(b, 'utf8')
  if (bufA.length !== bufB.length) return false
  return crypto.timingSafeEqual(bufA, bufB)
}

export function maskEmail(email: string): string {
  const trimmed = email.trim()
  const at = trimmed.indexOf('@')
  if (at <= 0) return trimmed.slice(0, 1) + '***'
  const local = trimmed.slice(0, at)
  const domain = trimmed.slice(at + 1)
  const masked = local.length <= 2 ? `${local[0]}***` : `${local[0]}***${local[local.length - 1]}`
  return `${masked}@${domain}`
}

export class AccountLinker {
  private readonly cfg: RelayConfig
  private readonly auth: RingAuthManager
  private readonly unclaimed = new Map<string, UnclaimedTokenRecord>()

  constructor(cfg: RelayConfig, auth: RingAuthManager) {
    this.cfg = cfg
    this.auth = auth
  }

  /**
   * Step 1 (Server-to-server from Ring): Token Exchange URL (`POST /ring/token-exchange`).
   * Ring POSTs `application/x-www-form-urlencoded` with `code=<authorization_code>`.
   */
  async handleTokenExchange(rawBody: Buffer, contentType: string): Promise<{ accountId: string }> {
    if (!this.cfg.clientId || !this.cfg.clientSecret) {
      throw new Error('RING_CLIENT_ID and RING_CLIENT_SECRET must be configured in relay/.env')
    }

    let code: string | undefined
    const bodyStr = rawBody.toString('utf8')
    if (contentType.includes('application/json')) {
      const parsed = JSON.parse(bodyStr) as { code?: string }
      code = parsed.code?.trim()
    } else {
      const params = new URLSearchParams(bodyStr)
      code = params.get('code')?.trim() || undefined
    }

    if (!code) {
      throw new Error('Missing authorization code in token-exchange request')
    }

    const tokenRes = await undiciFetch(RING_OAUTH_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        code,
        client_id: this.cfg.clientId,
        client_secret: this.cfg.clientSecret,
      }).toString(),
      dispatcher: upstreamAgent,
    })

    if (!tokenRes.ok) {
      const errText = await tokenRes.text()
      throw new Error(`Ring OAuth authorization_code exchange failed (${tokenRes.status}): ${errText}`)
    }

    const tokens = (await tokenRes.json()) as {
      access_token: string
      refresh_token: string
      expires_in?: number
    }

    if (!tokens.access_token || !tokens.refresh_token) {
      throw new Error('Ring OAuth response missing access_token or refresh_token')
    }

    const userRes = await undiciFetch(`${RING_API_BASE}/v1/users/me`, {
      method: 'GET',
      headers: { Authorization: `Bearer ${tokens.access_token}` },
      dispatcher: upstreamAgent,
    })

    if (!userRes.ok) {
      const errText = await userRes.text()
      throw new Error(`Failed to fetch /v1/users/me (${userRes.status}): ${errText}`)
    }

    const userJson = (await userRes.json()) as { data?: { id?: string } }
    const accountId = userJson.data?.id
    if (!accountId) {
      throw new Error('Ring /v1/users/me response missing data.id')
    }

    this.pruneExpired()
    this.unclaimed.set(accountId, {
      accessToken: tokens.access_token,
      refreshToken: tokens.refresh_token,
      expiresIn: tokens.expires_in ?? 14_400,
      accountId,
      createdAtMs: Date.now(),
    })

    console.log(`[link] Received & exchanged auth code for Ring account ${accountId} (awaiting browser confirmation)`)
    return { accountId }
  }

  /**
   * Step 2 (Browser redirect from Ring): validates `nonce` & `time` and renders a
   * minimal, secure confirmation page for Alex to claim and finalize the link.
   */
  renderLinkPage(nonce: string | null, timeParam: string | null): { status: number; html: string } {
    if (!nonce || !timeParam) {
      return {
        status: 400,
        html: '<h1>Missing nonce or time parameter</h1><p>Start linking from the Ring Developer Portal Connect page.</p>',
      }
    }

    const tsMs = Number(timeParam)
    const deltaSec = (Date.now() - tsMs) / 1000
    if (!Number.isFinite(tsMs) || deltaSec > 600 || deltaSec < -60) {
      return {
        status: 400,
        html: '<h1>Link request expired</h1><p>Please restart Connect from the Ring Developer Portal.</p>',
      }
    }

    const needsPasscode = Boolean(this.cfg.linkPasscode)
    const esc = (s: string) =>
      s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

    const html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1" />
  <title>Link Ring to Shield Pro</title>
  <style>
    body { font-family: system-ui, -apple-system, sans-serif; background: #0f141c; color: #e6edf3; display: grid; place-items: center; min-height: 100vh; margin: 0; padding: 16px; }
    .card { background: #161d29; border: 1px solid #2979ff55; border-radius: 14px; padding: 28px; max-width: 420px; width: 100%; box-shadow: 0 12px 40px #0008; }
    h1 { font-size: 20px; margin: 0 0 8px; }
    p { color: #9fb0c4; font-size: 14px; line-height: 1.5; margin: 0 0 18px; }
    label { display: block; font-size: 13px; color: #c7d4e3; margin: 12px 0 6px; }
    input { width: 100%; box-sizing: border-box; padding: 10px 12px; border-radius: 8px; border: 1px solid #2c384b; background: #0d121a; color: #fff; font-size: 14px; }
    button { width: 100%; margin-top: 18px; padding: 12px; border: 0; border-radius: 8px; background: #1976d2; color: #fff; font-weight: 600; font-size: 15px; cursor: pointer; }
    button:hover { background: #1e88e5; }
    #out { margin-top: 16px; font-size: 13px; white-space: pre-wrap; color: #8ce99a; }
    .err { color: #ff8787 !important; }
  </style>
</head>
<body>
  <div class="card">
    <h1>Connect Ring → Nvidia Shield Pro</h1>
    <p>Sign in with your email to verify ownership and finalize your Ring Private App link.</p>
    <form id="f">
      <input type="hidden" name="nonce" value="${esc(nonce)}" />
      <input type="hidden" name="time" value="${esc(timeParam)}" />
      <label>Your Email (shown masked in Ring App)</label>
      <input type="email" name="email" required placeholder="you@example.com" autofocus />
      ${
        needsPasscode
          ? `<label>Relay Passcode</label><input type="password" name="passcode" required placeholder="Enter LINK_PASSCODE / RELAY_LAN_TOKEN" />`
          : ''
      }
      <button type="submit">Confirm &amp; Link Ring Account</button>
    </form>
    <div id="out"></div>
  </div>
  <script>
    document.getElementById('f').addEventListener('submit', async (e) => {
      e.preventDefault();
      const out = document.getElementById('out');
      out.className = '';
      out.textContent = 'Finalizing link with Ring...';
      const fd = new FormData(e.target);
      const body = Object.fromEntries(fd.entries());
      const res = await fetch('/ring/complete-link', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      const json = await res.json().catch(() => ({ error: 'Invalid response' }));
      if (!res.ok) {
        out.className = 'err';
        out.textContent = 'Error: ' + (json.error || res.statusText);
      } else {
        out.textContent = 'Linked! Account: ' + json.account_id + '\\nRefresh token saved automatically — you can close this tab.';
      }
    });
  </script>
</body>
</html>`
    return { status: 200, html }
  }

  /**
   * Step 3: Matches nonce against unclaimed tokens, calls POST + PATCH
   * `/v1/accounts/me/app-integrations`, and persists the refresh token.
   */
  async completeLinking(params: {
    nonce?: string
    time?: string
    email?: string
    passcode?: string
  }): Promise<{ account_id: string; account_identifier: string }> {
    if (!this.cfg.hmacKey) {
      throw new Error('RING_HMAC_KEY must be configured in relay/.env')
    }
    if (this.cfg.linkPasscode && params.passcode !== this.cfg.linkPasscode) {
      throw new Error('Invalid relay passcode')
    }

    const nonce = params.nonce?.trim()
    const timeParam = params.time?.trim()
    const email = params.email?.trim()
    if (!nonce || !timeParam || !email) {
      throw new Error('Missing nonce, time, or email')
    }

    const tsMs = Number(timeParam)
    const deltaSec = (Date.now() - tsMs) / 1000
    if (!Number.isFinite(tsMs) || deltaSec > 600 || deltaSec < -60) {
      throw new Error('Link request expired (older than 600 seconds)')
    }

    this.pruneExpired()
    let matched: UnclaimedTokenRecord | null = null
    for (const rec of this.unclaimed.values()) {
      const expected = computeLinkingNonce(this.cfg.hmacKey, timeParam, rec.accountId)
      if (timingSafeEquals(expected, nonce)) {
        matched = rec
        break
      }
    }

    if (!matched) {
      throw new Error(
        `No matching unclaimed Ring token found (${this.unclaimed.size} pending). Make sure Token Exchange URL is reachable and retry Connect.`,
      )
    }

    const accountIdentifier = maskEmail(email)

    const postRes = await undiciFetch(`${RING_API_BASE}/v1/accounts/me/app-integrations`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${matched.accessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        account_identifier: accountIdentifier,
        nonce,
      }),
      dispatcher: upstreamAgent,
    })

    if (!postRes.ok) {
      const errText = await postRes.text()
      throw new Error(`Ring POST /app-integrations failed (${postRes.status}): ${errText}`)
    }

    const patchRes = await undiciFetch(`${RING_API_BASE}/v1/accounts/me/app-integrations`, {
      method: 'PATCH',
      headers: {
        Authorization: `Bearer ${matched.accessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ status: 'completed' }),
      dispatcher: upstreamAgent,
    })

    if (!patchRes.ok) {
      const errText = await patchRes.text()
      throw new Error(`Ring PATCH /app-integrations failed (${patchRes.status}): ${errText}`)
    }

    this.unclaimed.delete(matched.accountId)
    this.auth.installTokens(matched.accessToken, matched.refreshToken, matched.expiresIn)

    console.log(`[link] Account linking COMPLETED for ${matched.accountId} (${accountIdentifier})`)
    return { account_id: matched.accountId, account_identifier: accountIdentifier }
  }

  private pruneExpired(): void {
    const cutoff = Date.now() - UNCLAIMED_TTL_MS
    for (const [id, rec] of this.unclaimed) {
      if (rec.createdAtMs < cutoff) this.unclaimed.delete(id)
    }
  }
}
