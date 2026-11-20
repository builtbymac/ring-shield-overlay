import { performance } from 'node:perf_hooks'
import { fetch as undiciFetch } from 'undici'
import { RING_API_BASE, RingAuthManager, upstreamAgent } from './ringAuth.js'

// Ring WAF requires User-Agent & Hardware_Id headers to avoid 406 Not Acceptable
const RING_CLIENT_HEADERS = {
  'User-Agent': 'ShieldDoorbellOverlay/0.1.0',
  Hardware_Id: '9f8e7d6c-5b4a-4321-9876-543210fedcba',
}

export interface WhepStartResult {
  sdpAnswer: string
  sessionUrl: string | null
  ringRttMs: number
}

export async function startWhepSession(
  auth: RingAuthManager,
  deviceId: string,
  sdpOffer: string,
): Promise<WhepStartResult> {
  const token = await auth.getAccessToken()
  const whepUrl = `${RING_API_BASE}/v1/devices/${encodeURIComponent(deviceId)}/media/streaming/whep/sessions`

  const t0 = performance.now()
  const res = await undiciFetch(whepUrl, {
    method: 'POST',
    headers: {
      ...RING_CLIENT_HEADERS,
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/sdp',
    },
    body: sdpOffer,
    dispatcher: upstreamAgent,
  })
  const ringRttMs = Math.round((performance.now() - t0) * 10) / 10

  if (!res.ok) {
    const errText = await res.text()
    throw new Error(`Ring WHEP POST failed (${res.status}) in ${ringRttMs} ms: ${errText}`)
  }

  const sdpAnswer = await res.text()
  const sessionUrl = res.headers.get('location')
  const summaryLines = sdpAnswer
    .split(/\r?\n/)
    .filter((l) => /^(m=|a=rtpmap:|a=fmtp:|a=candidate:|a=(sendrecv|recvonly|sendonly|inactive))/.test(l))
  console.log(`[whep] Answer SDP summary (${summaryLines.length} lines):\n  ${summaryLines.slice(0, 14).join('\n  ')}`)
  return { sdpAnswer, sessionUrl, ringRttMs }
}

export async function stopWhepSession(
  auth: RingAuthManager,
  sessionUrl: string,
): Promise<void> {
  // Guard against SSRF: only allow DELETE to Amazon Vision / Ring session URLs
  const parsed = new URL(sessionUrl)
  if (parsed.protocol !== 'https:' || !parsed.hostname.endsWith('amazonvision.com')) {
    throw new Error(`Refusing WHEP DELETE to unexpected host: ${parsed.hostname}`)
  }

  const token = await auth.getAccessToken()
  const res = await undiciFetch(sessionUrl, {
    method: 'DELETE',
    headers: { ...RING_CLIENT_HEADERS, Authorization: `Bearer ${token}` },
    dispatcher: upstreamAgent,
  })
  if (!res.ok && res.status !== 404) {
    const errText = await res.text()
    throw new Error(`Ring WHEP DELETE failed (${res.status}): ${errText}`)
  }
}

export async function discoverDevices(auth: RingAuthManager): Promise<unknown[]> {
  const token = await auth.getAccessToken()
  const res = await undiciFetch(`${RING_API_BASE}/v1/devices`, {
    method: 'GET',
    headers: { ...RING_CLIENT_HEADERS, Authorization: `Bearer ${token}` },
    dispatcher: upstreamAgent,
  })
  if (!res.ok) {
    const errText = await res.text()
    throw new Error(`Device discovery failed (${res.status}): ${errText}`)
  }
  const body = (await res.json()) as { data?: any[] }
  return (body.data ?? []).map((d) => ({
    id: d.id,
    name: d.attributes?.name ?? d.attributes?.description ?? 'Ring Device',
    online: d.attributes?.online ?? null,
    capabilities: d.attributes?.capabilities ?? {},
    rawType: d.type,
  }))
}
