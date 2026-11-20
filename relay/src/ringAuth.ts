import fs from 'node:fs'
import path from 'node:path'
import { Agent, fetch as undiciFetch } from 'undici'
import type { RelayConfig } from './config.js'

// Persistent TLS keep-alive pool to api.amazonvision.com & oauth.ring.com so
// WHEP SDP requests never pay a cold TCP+TLS handshake on doorbell press.
export const upstreamAgent = new Agent({
  connections: 8,
  keepAliveTimeout: 115_000,
  keepAliveMaxTimeout: 300_000,
})

export const RING_API_BASE = 'https://api.amazonvision.com'
const RING_OAUTH_URL = 'https://oauth.ring.com/oauth/token'

interface PersistedTokens {
  refreshToken: string
  updatedAt: string
}

interface CachedAccessToken {
  token: string
  expiresAtMs: number
}

export class RingAuthManager {
  private readonly cfg: RelayConfig
  private cached: CachedAccessToken | null = null
  private currentRefreshToken?: string
  private inflightRefresh: Promise<string> | null = null
  private renewTimer: NodeJS.Timeout | null = null

  constructor(cfg: RelayConfig) {
    this.cfg = cfg
    this.currentRefreshToken = this.loadPersistedRefreshToken() ?? cfg.refreshToken
  }

  get mode(): 'access_token' | 'refresh_token' | 'unconfigured' {
    if (this.currentRefreshToken) return 'refresh_token'
    if (this.cfg.accessToken) return 'access_token'
    return 'unconfigured'
  }

  /**
   * Installs fresh OAuth tokens obtained from Ring's one-way account linking flow,
   * persists the refresh token to disk, and arms proactive renewal immediately.
   */
  installTokens(accessToken: string, refreshToken: string, expiresInSec = 14_400): void {
    this.currentRefreshToken = refreshToken
    this.persistRefreshToken(refreshToken)
    const refreshLeadMs = Math.min(90_000, Math.floor((expiresInSec * 1000) / 4))
    this.cached = {
      token: accessToken,
      expiresAtMs: Date.now() + expiresInSec * 1000 - refreshLeadMs,
    }
    this.scheduleProactiveRefresh(expiresInSec * 1000 - refreshLeadMs)
    console.log(`[auth] Installed linked OAuth tokens (expires_in=${expiresInSec}s, persisted to ${this.cfg.tokenStorePath})`)
  }

  async getAccessToken(): Promise<string> {
    if (this.currentRefreshToken) {
      if (this.cached && Date.now() < this.cached.expiresAtMs) {
        return this.cached.token
      }
      return this.refreshAccessToken()
    }

    if (this.cfg.accessToken) {
      return this.cfg.accessToken
    }

    throw new Error('No Ring credentials configured (link your Private App or set RING_ACCESS_TOKEN / RING_REFRESH_TOKEN).')
  }

  /** Pre-warm the access token and schedule proactive renewal 90 s before expiry. */
  async warm(): Promise<void> {
    if (this.mode === 'unconfigured') return
    try {
      await this.getAccessToken()
      console.log(`[auth] Ring auth warmed (mode=${this.mode})`)
    } catch (err) {
      console.warn('[auth] Initial token warm failed:', (err as Error).message)
    }
  }

  stop(): void {
    if (this.renewTimer) {
      clearTimeout(this.renewTimer)
      this.renewTimer = null
    }
  }

  private async refreshAccessToken(): Promise<string> {
    if (this.inflightRefresh) return this.inflightRefresh

    this.inflightRefresh = (async () => {
      if (!this.cfg.clientId || !this.cfg.clientSecret) {
        throw new Error('RING_CLIENT_ID and RING_CLIENT_SECRET are required for refresh token mode.')
      }
      if (!this.currentRefreshToken) {
        throw new Error('Missing RING_REFRESH_TOKEN.')
      }

      const body = new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: this.currentRefreshToken,
        client_id: this.cfg.clientId,
        client_secret: this.cfg.clientSecret,
      })

      const res = await undiciFetch(RING_OAUTH_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: body.toString(),
        dispatcher: upstreamAgent,
      })

      if (!res.ok) {
        const errText = await res.text()
        throw new Error(`Ring OAuth refresh failed (${res.status}): ${errText}`)
      }

      const data = (await res.json()) as {
        access_token: string
        expires_in?: number
        refresh_token?: string
      }

      if (!data.access_token) {
        throw new Error('Ring OAuth response missing access_token')
      }

      if (data.refresh_token && data.refresh_token !== this.currentRefreshToken) {
        this.currentRefreshToken = data.refresh_token
        this.persistRefreshToken(data.refresh_token)
      }

      const ttlSec = data.expires_in ?? 14_400
      const refreshLeadMs = Math.min(90_000, Math.floor((ttlSec * 1000) / 4))
      this.cached = {
        token: data.access_token,
        expiresAtMs: Date.now() + ttlSec * 1000 - refreshLeadMs,
      }
      this.scheduleProactiveRefresh(ttlSec * 1000 - refreshLeadMs)

      return data.access_token
    })().finally(() => {
      this.inflightRefresh = null
    })

    return this.inflightRefresh
  }

  private scheduleProactiveRefresh(delayMs: number): void {
    if (this.renewTimer) clearTimeout(this.renewTimer)
    const bounded = Math.max(30_000, delayMs)
    this.renewTimer = setTimeout(() => {
      this.refreshAccessToken().catch((err) => {
        console.error('[auth] Proactive token refresh failed:', (err as Error).message)
      })
    }, bounded)
    this.renewTimer.unref()
  }

  private loadPersistedRefreshToken(): string | undefined {
    try {
      const raw = fs.readFileSync(this.cfg.tokenStorePath, 'utf8')
      const parsed = JSON.parse(raw) as Partial<PersistedTokens>
      return parsed.refreshToken?.trim() || undefined
    } catch {
      return undefined
    }
  }

  private persistRefreshToken(refreshToken: string): void {
    try {
      const dir = path.dirname(this.cfg.tokenStorePath)
      fs.mkdirSync(dir, { recursive: true })
      const tmp = `${this.cfg.tokenStorePath}.tmp`
      const payload: PersistedTokens = {
        refreshToken,
        updatedAt: new Date().toISOString(),
      }
      fs.writeFileSync(tmp, JSON.stringify(payload, null, 2), { mode: 0o600 })
      fs.renameSync(tmp, this.cfg.tokenStorePath)
    } catch (err) {
      console.error('[auth] Failed to persist rotated refresh token:', (err as Error).message)
    }
  }
}
