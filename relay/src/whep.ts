import { performance } from 'node:perf_hooks'
import tls from 'node:tls'
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

let lastCapturedSdpOffer: string | null = null

export function getLastCapturedSdpOffer(): string | null {
  return lastCapturedSdpOffer
}

/**
 * Rewrites an SDP offer's video section to either:
 * - 'h265_only': strip H264/VP8/VP9/AV1 and offer only H265/90000 (profile-id=1)
 * - 'h265_first': inject or move H265/90000 to the front of m=video ahead of H264
 */
export function rewriteSdpForHevc(sdp: string, mode: 'h265_only' | 'h265_first'): string {
  const lines = sdp.replace(/\r\n/g, '\n').split('\n')
  if (mode === 'h265_only') {
    const out: string[] = []
    let inVideo = false
    for (const line of lines) {
      if (line.startsWith('m=video ')) {
        inVideo = true
        const parts = line.split(' ')
        out.push(`${parts[0]} ${parts[1]} ${parts[2]} 125 126`)
        continue
      }
      if (line.startsWith('m=') && !line.startsWith('m=video ')) {
        if (inVideo) {
          out.push(
            'a=rtpmap:125 H265/90000',
            'a=fmtp:125 profile-id=1',
            'a=rtcp-fb:125 nack',
            'a=rtcp-fb:125 nack pli',
            'a=rtcp-fb:125 goog-remb',
            'a=rtpmap:126 rtx/90000',
            'a=fmtp:126 apt=125',
          )
        }
        inVideo = false
      }
      if (inVideo && /^(a=rtpmap:|a=fmtp:|a=rtcp-fb:)/.test(line)) {
        continue
      }
      out.push(line)
    }
    if (inVideo) {
      // Insert before trailing empty line
      const insertAt = out[out.length - 1] === '' ? out.length - 1 : out.length
      out.splice(
        insertAt,
        0,
        'a=rtpmap:125 H265/90000',
        'a=fmtp:125 profile-id=1',
        'a=rtcp-fb:125 nack',
        'a=rtcp-fb:125 nack pli',
        'a=rtcp-fb:125 goog-remb',
        'a=rtpmap:126 rtx/90000',
        'a=fmtp:126 apt=125',
      )
    }
    return out.join('\r\n')
  }

  // h265_first: place PT 125 (H265) first in m=video
  const out: string[] = []
  let inVideo = false
  let injected = false
  for (const line of lines) {
    if (line.startsWith('m=video ')) {
      inVideo = true
      const parts = line.split(' ')
      out.push(`${parts[0]} ${parts[1]} ${parts[2]} 125 ${parts.slice(3).join(' ')}`)
      continue
    }
    if (inVideo && !injected && line.startsWith('a=rtpmap:')) {
      out.push(
        'a=rtpmap:125 H265/90000',
        'a=fmtp:125 profile-id=1',
        'a=rtcp-fb:125 nack',
        'a=rtcp-fb:125 nack pli',
      )
      injected = true
    }
    out.push(line)
  }
  return out.join('\r\n')
}

/**
 * When the client (Shield Pro Tegra X1+) already offers native H265/90000 in its WebRTC SDP
 * offer alongside H264/90000, Ring's WHEP gateway defaults to H264 (which routes through Ring's
 * 720p H.264 transcoder). By stripping H264/VP8/VP9/AV1 from the outbound offer sent to Ring while
 * preserving the client's exact H265 payload type (e.g. PT 104 + RTX 105), Ring's WHEP endpoint
 * negotiates native H265 directly using the client's original payload type numbers.
 */
export function preferClientHevcInOffer(sdp: string): string {
  const lines = sdp.replace(/\r\n/g, '\n').split('\n')
  const h265Match = lines.find((l) => /^a=rtpmap:(\d+)\s+H265\/90000/i.test(l))
  if (!h265Match) return sdp
  const h265Pt = h265Match.match(/^a=rtpmap:(\d+)/)![1]
  const rtxFmtp = lines.find((l) => new RegExp(`^a=fmtp:(\\d+)\\s+apt=${h265Pt}\\b`).test(l))
  const rtxPt = rtxFmtp ? rtxFmtp.match(/^a=fmtp:(\d+)/)![1] : null
  const allowedPts = new Set([h265Pt, ...(rtxPt ? [rtxPt] : [])])

  const out: string[] = []
  let inVideo = false
  for (const line of lines) {
    if (line.startsWith('m=video ')) {
      inVideo = true
      const parts = line.split(' ')
      const keptPts = parts.slice(3).filter((pt) => allowedPts.has(pt))
      out.push(`${parts[0]} ${parts[1]} ${parts[2]} ${keptPts.join(' ')}`)
      continue
    }
    if (line.startsWith('m=') && !line.startsWith('m=video ')) {
      inVideo = false
    }
    if (inVideo) {
      const ptMatch = line.match(/^a=(?:rtpmap|fmtp|rtcp-fb):(\d+)\b/)
      if (ptMatch) {
        const pt = ptMatch[1]
        if (!allowedPts.has(pt)) continue
        if (pt === h265Pt && line.startsWith(`a=fmtp:${h265Pt} `)) {
          out.push(`a=fmtp:${h265Pt} profile-id=1`)
          continue
        }
      }
    }
    out.push(line)
  }
  return out.join('\r\n')
}

export async function startWhepSession(
  auth: RingAuthManager,
  deviceId: string,
  sdpOffer: string,
): Promise<WhepStartResult> {
  lastCapturedSdpOffer = sdpOffer
  const outboundOffer = process.env.RING_FORCE_HEVC === 'true' ? preferClientHevcInOffer(sdpOffer) : sdpOffer
  const token = await auth.getAccessToken()
  const whepUrl = `${RING_API_BASE}/v1/devices/${encodeURIComponent(deviceId)}/media/streaming/whep/sessions`

  const offerSummary = outboundOffer
    .split(/\r?\n/)
    .filter((l) => /^(m=|a=rtpmap:|a=fmtp:)/.test(l))
  console.log(`[whep] Outbound Offer SDP summary (${offerSummary.length} lines):\n  ${offerSummary.join('\n  ')}`)

  const t0 = performance.now()
  const res = await undiciFetch(whepUrl, {
    method: 'POST',
    headers: {
      ...RING_CLIENT_HEADERS,
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/sdp',
    },
    body: outboundOffer,
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
  const res = await undiciFetch(`${RING_API_BASE}/v1/devices?include=status,capabilities,configurations`, {
    method: 'GET',
    headers: { ...RING_CLIENT_HEADERS, Authorization: `Bearer ${token}` },
    dispatcher: upstreamAgent,
  })
  if (!res.ok) {
    const errText = await res.text()
    throw new Error(`Device discovery failed (${res.status}): ${errText}`)
  }
  const body = (await res.json()) as { data?: any[]; included?: any[] }
  const incByKey = new Map<string, any>()
  for (const item of body.included ?? []) {
    incByKey.set(`${item.type}:${item.id}`, item.attributes ?? {})
  }
  const lookup = (rel: any) => {
    const ref = rel?.data
    return ref ? incByKey.get(`${ref.type}:${ref.id}`) ?? {} : {}
  }
  return (body.data ?? []).map((d) => ({
    id: d.id,
    name: d.attributes?.name ?? d.attributes?.description ?? 'Ring Device',
    status: lookup(d.relationships?.status),
    capabilities: lookup(d.relationships?.capabilities),
    configurations: lookup(d.relationships?.configurations),
    rawType: d.type,
  }))
}

export async function probeRtspStream(
  auth: RingAuthManager,
  deviceId: string,
): Promise<{ describeResponse: string; teardownResponse?: string; rtspBasicUrl: string }> {
  const token = await auth.getAccessToken()
  const host = 'video.rtsp.amazonvision.com'
  const port = 322
  const uri = `rtsps://${host}:${port}/v1/devices/${deviceId}/stream`
  const rtspBasicUrl = `rtsps://:${token}@${host}:${port}/v1/devices/${deviceId}/stream`

  return new Promise((resolve, reject) => {
    const sock = tls.connect({ host, port, servername: host }, () => {
      const req =
        `DESCRIBE ${uri} RTSP/1.0\r\n` +
        `CSeq: 1\r\n` +
        `Accept: application/sdp\r\n` +
        `X-Auth-Token: ${token}\r\n` +
        `User-Agent: ShieldDoorbellOverlay/0.1.0\r\n\r\n`
      sock.write(req)
    })

    let buf = ''
    let stage: 'describe' | 'teardown' = 'describe'
    let describeResp = ''
    const timer = setTimeout(() => {
      sock.destroy()
      resolve({ describeResponse: buf || 'timeout', rtspBasicUrl })
    }, 8000)

    sock.on('data', (chunk: Buffer) => {
      buf += chunk.toString('utf8')
      if (stage === 'describe' && buf.includes('\r\n\r\n')) {
        const headerEnd = buf.indexOf('\r\n\r\n')
        const headers = buf.slice(0, headerEnd)
        const clMatch = headers.match(/Content-Length:\s*(\d+)/i)
        const contentLen = clMatch ? Number(clMatch[1]) : 0
        if (Buffer.byteLength(buf.slice(headerEnd + 4), 'utf8') >= contentLen) {
          describeResp = buf
          const sessMatch = headers.match(/Session:\s*([^\r\n;]+)/i)
          if (sessMatch) {
            stage = 'teardown'
            buf = ''
            const td =
              `TEARDOWN ${uri} RTSP/1.0\r\n` +
              `CSeq: 2\r\n` +
              `Session: ${sessMatch[1].trim()}\r\n\r\n`
            sock.write(td)
          } else {
            clearTimeout(timer)
            sock.end()
            resolve({ describeResponse: describeResp, rtspBasicUrl })
          }
        }
      } else if (stage === 'teardown' && buf.includes('\r\n\r\n')) {
        clearTimeout(timer)
        sock.end()
        resolve({ describeResponse: describeResp, teardownResponse: buf, rtspBasicUrl })
      }
    })

    sock.on('error', (err) => {
      clearTimeout(timer)
      reject(err)
    })
  })
}
