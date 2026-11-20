import 'dotenv/config'

export interface RelayConfig {
  port: number
  host: string
  accessToken?: string
  clientId?: string
  clientSecret?: string
  refreshToken?: string
  hmacKey?: string
  webhookSecret?: string
  defaultDeviceId?: string
  lanToken?: string
  linkPasscode?: string
  tokenStorePath: string
}

function opt(name: string): string | undefined {
  const v = process.env[name]?.trim()
  return v ? v : undefined
}

export function loadConfig(): RelayConfig {
  const port = Number.parseInt(process.env.PORT || '8787', 10)
  return {
    port: Number.isFinite(port) ? port : 8787,
    host: process.env.HOST?.trim() || '0.0.0.0',
    accessToken: opt('RING_ACCESS_TOKEN'),
    clientId: opt('RING_CLIENT_ID'),
    clientSecret: opt('RING_CLIENT_SECRET'),
    refreshToken: opt('RING_REFRESH_TOKEN'),
    hmacKey: opt('RING_HMAC_KEY'),
    webhookSecret: opt('RING_WEBHOOK_SECRET'),
    defaultDeviceId: opt('RING_DEVICE_ID'),
    lanToken: opt('RELAY_LAN_TOKEN'),
    linkPasscode: opt('LINK_PASSCODE') ?? opt('RELAY_LAN_TOKEN'),
    tokenStorePath: opt('TOKEN_STORE_PATH') || './data/tokens.json',
  }
}
