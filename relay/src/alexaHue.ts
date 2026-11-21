import dgram from 'node:dgram'
import http from 'node:http'
import os from 'node:os'
import type { OverlayCommandAction, ShieldWsHub } from './wsHub.js'

export interface AlexaHueOptions {
  enabled?: boolean
  port?: number
  hostIp?: string
  hub: ShieldWsHub
}

interface VirtualLight {
  id: string
  name: string
  uniqueid: string
  onAction: OverlayCommandAction
  offAction: OverlayCommandAction
  on: boolean
  bri: number
}

const SSDP_MULTICAST_ADDR = '239.255.255.250'
const SSDP_PORT = 1900
const BRIDGE_MAC = '00:17:88:a1:e2:01'
const BRIDGE_ID = '001788FFFEA1E201'
const BRIDGE_UUID = '2f402f80-da50-11e1-9b23-001788a1e201'
const HUE_USERNAME = 'ringshieldhueuser0000000001'

export function detectLanIpv4(): string {
  const ifaces = os.networkInterfaces()
  for (const entries of Object.values(ifaces)) {
    if (!entries) continue
    for (const info of entries) {
      if (info.family === 'IPv4' && !info.internal && !info.address.startsWith('169.254.')) {
        return info.address
      }
    }
  }
  return '127.0.0.1'
}

/**
 * Local Philips Hue Bridge v1 emulator for zero-cloud Amazon Alexa integration.
 *
 * Exposes two virtual dimmable switches on LAN port 80 + UPnP SSDP (239.255.255.250:1900):
 *  - "Front Door View"       -> Turn ON: PiP overlay (`show`),       Turn OFF: `dismiss`
 *  - "Front Door Fullscreen" -> Turn ON: Fullscreen (`fullscreen`),  Turn OFF: `dismiss`
 *
 * Pair once with "Alexa, discover my devices", then create Alexa Routines for natural phrases
 * like "Show me my doorbell", "Show me the front door", or "Show me the door".
 */
export class AlexaHueBridge {
  private readonly port: number
  private readonly hostIp: string
  private readonly hub: ShieldWsHub
  private readonly lights: Map<string, VirtualLight>
  private httpServer: http.Server | null = null
  private ssdpSocket: dgram.Socket | null = null

  constructor(opts: AlexaHueOptions) {
    this.port = opts.port ?? 80
    this.hostIp = opts.hostIp?.trim() || detectLanIpv4()
    this.hub = opts.hub
    this.lights = new Map([
      [
        '1',
        {
          id: '1',
          name: 'Front Door View',
          uniqueid: '00:17:88:01:00:b0:00:01-0b',
          onAction: 'show',
          offAction: 'dismiss',
          on: false,
          bri: 254,
        },
      ],
      [
        '2',
        {
          id: '2',
          name: 'Front Door Fullscreen',
          uniqueid: '00:17:88:01:00:b0:00:02-0b',
          onAction: 'fullscreen',
          offAction: 'dismiss',
          on: false,
          bri: 254,
        },
      ],
    ])
  }

  async start(): Promise<void> {
    this.httpServer = http.createServer((req, res) => {
      this.handleHttp(req, res).catch((err) => {
        console.warn('[alexa-hue] HTTP error:', (err as Error).message)
        if (!res.headersSent) {
          res.writeHead(500, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify([{ error: { type: 901, description: 'Internal error' } }]))
        }
      })
    })
    this.httpServer.keepAliveTimeout = 65_000
    this.httpServer.headersTimeout = 66_000

    await new Promise<void>((resolve) => {
      const srv = this.httpServer!
      srv.once('error', (err: NodeJS.ErrnoException) => {
        console.warn(
          `[alexa-hue] Could not bind HTTP port ${this.port} (${err.code ?? err.message}); Alexa Hue emulator disabled.`,
        )
        this.httpServer = null
        resolve()
      })
      srv.listen(this.port, '0.0.0.0', () => {
        console.log(
          `[alexa-hue] Hue Bridge emulator listening on http://${this.hostIp}:${this.port} ` +
            `(devices: "Front Door View", "Front Door Fullscreen")`,
        )
        this.startSsdp()
        resolve()
      })
    })
  }

  /**
   * Momentary push-button mode: virtual Hue lights always report `on: false` on GET polls so
   * Alexa never caches a stale "On" state (which causes Hue v1 Routines to skip future "Turn On"
   * PUTs and leaves the Alexa phone app stuck showing the light as "On").
   */
  syncOverlayState(_showing: boolean, _fullscreen: boolean): void {
    for (const light of this.lights.values()) {
      light.on = false
    }
  }

  stop(): void {
    if (this.ssdpSocket) {
      try {
        this.ssdpSocket.close()
      } catch {}
      this.ssdpSocket = null
    }
    if (this.httpServer) {
      try {
        this.httpServer.close()
      } catch {}
      this.httpServer = null
    }
  }

  /** Exposed for automated tests without requiring port 80 or multicast sockets. */
  async handleHttp(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const method = req.method || 'GET'
    const url = new URL(req.url || '/', `http://${this.hostIp}:${this.port}`)
    const path = url.pathname

    if (method === 'GET' && path === '/description.xml') {
      const xml = this.renderDescriptionXml()
      res.writeHead(200, {
        'Content-Type': 'application/xml; charset=utf-8',
        'Content-Length': Buffer.byteLength(xml),
      })
      res.end(xml)
      return
    }

    if (method === 'POST' && (path === '/api' || path === '/api/')) {
      await readBody(req)
      sendJson(res, [{ success: { username: HUE_USERNAME } }])
      return
    }

    if (method === 'GET' && (path === '/api/config' || /^\/api\/[^/]+\/config\/?$/.test(path))) {
      sendJson(res, this.renderConfig())
      return
    }

    // Full datastore GET /api/<user>
    if (method === 'GET' && /^\/api\/[^/]+\/?$/.test(path)) {
      sendJson(res, {
        lights: this.renderLightsMap(),
        groups: {},
        config: this.renderConfig(),
        schedules: {},
        scenes: {},
        rules: {},
        sensors: {},
        resourcelinks: {},
      })
      return
    }

    // List all lights GET /api/<user>/lights
    if (method === 'GET' && /^\/api\/[^/]+\/lights\/?$/.test(path)) {
      sendJson(res, this.renderLightsMap())
      return
    }

    // Single light GET /api/<user>/lights/<id>
    const singleMatch = path.match(/^\/api\/[^/]+\/lights\/([^/]+)\/?$/)
    if (method === 'GET' && singleMatch) {
      const light = this.lights.get(singleMatch[1])
      if (!light) {
        sendJson(res, [{ error: { type: 3, address: path, description: 'resource not available' } }], 404)
        return
      }
      sendJson(res, this.renderLight(light))
      return
    }

    // State update PUT /api/<user>/lights/<id>/state
    const stateMatch = path.match(/^\/api\/[^/]+\/lights\/([^/]+)\/state\/?$/)
    if (method === 'PUT' && stateMatch) {
      const light = this.lights.get(stateMatch[1])
      if (!light) {
        sendJson(res, [{ error: { type: 3, address: path, description: 'resource not available' } }], 404)
        return
      }
      const raw = await readBody(req)
      const body = raw ? (JSON.parse(raw) as Record<string, unknown>) : {}
      const results: Array<{ success: Record<string, unknown> }> = []

      let dispatchedAction: OverlayCommandAction | null = null

      if (typeof body.on === 'boolean') {
        const desiredOn = body.on
        // Return the requested state in the PUT confirmation so Alexa succeeds, while keeping
        // the stored virtual switch `on = false` (momentary trigger) so follow-up GET polls
        // never leave Alexa stuck in a cached "On" state.
        light.on = false
        results.push({ success: { [`/lights/${light.id}/state/on`]: desiredOn } })
        dispatchedAction = desiredOn ? light.onAction : light.offAction
      }

      if (typeof body.bri === 'number' && Number.isFinite(body.bri)) {
        light.bri = Math.max(1, Math.min(254, Math.round(body.bri)))
        results.push({ success: { [`/lights/${light.id}/state/bri`]: light.bri } })
        if (!dispatchedAction && light.bri > 0) {
          dispatchedAction = light.onAction
        }
      }

      if (dispatchedAction) {
        const delivered = this.hub.broadcastCommand(dispatchedAction)
        console.log(
          `[alexa-hue] "${light.name}" (${JSON.stringify(body)}) => Shield action=${dispatchedAction} (delivered=${delivered})`,
        )
      }

      if (results.length === 0) {
        results.push({ success: { [`/lights/${light.id}/state/on`]: light.on } })
      }
      sendJson(res, results)
      return
    }

    sendJson(res, {})
  }

  private startSsdp(): void {
    try {
      const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true })
      sock.on('error', (err) => {
        console.warn('[alexa-hue] SSDP socket error:', err.message)
      })
      sock.on('message', (msg, rinfo) => {
        const text = msg.toString('utf8')
        if (!text.startsWith('M-SEARCH')) return
        const upper = text.toUpperCase()
        const wantsBridge =
          upper.includes('SSDP:ALL') ||
          upper.includes('UPNP:ROOTDEVICE') ||
          upper.includes('URN:SCHEMAS-UPNP-ORG:DEVICE:BASIC:1') ||
          upper.includes(BRIDGE_UUID.toUpperCase())
        if (!wantsBridge) return

        const responses = [
          this.buildSsdpResponse('upnp:rootdevice', `uuid:${BRIDGE_UUID}::upnp:rootdevice`),
          this.buildSsdpResponse('urn:schemas-upnp-org:device:basic:1', `uuid:${BRIDGE_UUID}`),
        ]
        for (const payload of responses) {
          const buf = Buffer.from(payload, 'utf8')
          sock.send(buf, 0, buf.length, rinfo.port, rinfo.address, () => {})
        }
      })
      sock.bind(SSDP_PORT, () => {
        try {
          sock.addMembership(SSDP_MULTICAST_ADDR)
          console.log(`[alexa-hue] SSDP discovery active on ${SSDP_MULTICAST_ADDR}:${SSDP_PORT}`)
        } catch (err) {
          console.warn('[alexa-hue] SSDP addMembership warning:', (err as Error).message)
        }
      })
      this.ssdpSocket = sock
    } catch (err) {
      console.warn('[alexa-hue] Could not start SSDP responder:', (err as Error).message)
    }
  }

  private buildSsdpResponse(st: string, usn: string): string {
    return (
      'HTTP/1.1 200 OK\r\n' +
      `HOST: ${SSDP_MULTICAST_ADDR}:${SSDP_PORT}\r\n` +
      'EXT:\r\n' +
      'CACHE-CONTROL: max-age=100\r\n' +
      `LOCATION: http://${this.hostIp}:${this.port}/description.xml\r\n` +
      'SERVER: Linux/3.14.0 UPnP/1.0 IpBridge/1.20.0\r\n' +
      `hue-bridgeid: ${BRIDGE_ID}\r\n` +
      `ST: ${st}\r\n` +
      `USN: ${usn}\r\n\r\n`
    )
  }

  private renderDescriptionXml(): string {
    return `<?xml version="1.0" encoding="UTF-8" ?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <URLBase>http://${this.hostIp}:${this.port}/</URLBase>
  <device>
    <deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>
    <friendlyName>Philips hue (${this.hostIp})</friendlyName>
    <manufacturer>Royal Philips Electronics</manufacturer>
    <manufacturerURL>http://www.philips.com</manufacturerURL>
    <modelDescription>Philips hue Personal Wireless Lighting</modelDescription>
    <modelName>Philips hue bridge 2012</modelName>
    <modelNumber>929000226503</modelNumber>
    <modelURL>http://www.meethue.com</modelURL>
    <serialNumber>${BRIDGE_ID.toLowerCase()}</serialNumber>
    <UDN>uuid:${BRIDGE_UUID}</UDN>
    <presentationURL>index.html</presentationURL>
  </device>
</root>`
  }

  private renderConfig(): Record<string, unknown> {
    return {
      name: 'Shield Doorbell Hue Bridge',
      datastoreversion: '98',
      swversion: '1941132070',
      apiversion: '1.38.0',
      mac: BRIDGE_MAC,
      bridgeid: BRIDGE_ID,
      factorynew: false,
      replacesbridgeid: null,
      modelid: 'BSB002',
      starterkitid: '',
      ipaddress: this.hostIp,
    }
  }

  private renderLightsMap(): Record<string, unknown> {
    const out: Record<string, unknown> = {}
    for (const [id, light] of this.lights.entries()) {
      out[id] = this.renderLight(light)
    }
    return out
  }

  private renderLight(light: VirtualLight): Record<string, unknown> {
    return {
      state: {
        on: light.on,
        bri: light.bri,
        alert: 'none',
        mode: 'homeautomation',
        reachable: true,
      },
      swupdate: {
        state: 'noupdates',
        lastinstall: '2026-01-01T00:00:00',
      },
      type: 'Dimmable light',
      name: light.name,
      modelid: 'LWB010',
      manufacturername: 'Signify Netherlands B.V.',
      productname: 'Hue white lamp',
      capabilities: {
        certified: true,
        control: { mindimlevel: 5000, maxlumen: 806 },
        streaming: { renderer: false, proxy: false },
      },
      config: {
        archetype: 'classicbulb',
        function: 'functional',
        direction: 'omnidirectional',
      },
      uniqueid: light.uniqueid,
      swversion: '1.46.13_r26312',
    }
  }
}

function sendJson(res: http.ServerResponse, body: unknown, status = 200): void {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
  })
  res.end(payload)
}

function readBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    req.on('data', (c: Buffer) => chunks.push(c))
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}
