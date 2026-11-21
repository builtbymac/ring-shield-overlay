import type { IncomingMessage } from 'node:http'
import { WebSocketServer, WebSocket } from 'ws'
import type { Server } from 'node:http'
import type { NormalizedRingEvent } from './webhook.js'

export type OverlayCommandAction = 'show' | 'fullscreen' | 'toggle_fullscreen' | 'dismiss'

interface AliveSocket extends WebSocket {
  missedPongs?: number
  clientId?: string
}

export interface OverlayLiveState {
  showing: boolean
  fullscreen: boolean
}

/**
 * Ultra-low-latency LAN WebSocket broadcast hub for Shield Pro clients.
 * Keeps TCP sockets warm with 20 s WebSocket pings so notifications arrive in <1 ms.
 */
export class ShieldWsHub {
  private readonly wss: WebSocketServer
  private readonly pingTimer: NodeJS.Timeout
  private readonly stateListeners = new Set<(state: OverlayLiveState) => void>()
  private currentOverlayState: OverlayLiveState = { showing: false, fullscreen: false }

  constructor(server: Server, lanToken?: string) {
    this.wss = new WebSocketServer({ noServer: true })

    server.on('upgrade', (req: IncomingMessage, socket, head) => {
      const url = new URL(req.url || '/', 'http://localhost')
      if (url.pathname !== '/ws') {
        socket.destroy()
        return
      }
      if (req.headers['cf-ray'] || req.headers['cf-connecting-ip']) {
        socket.write('HTTP/1.1 403 Forbidden\r\n\r\n')
        socket.destroy()
        return
      }
      if (lanToken) {
        const provided =
          url.searchParams.get('token') ??
          req.headers.authorization?.replace(/^Bearer\s+/i, '').trim()
        if (provided !== lanToken) {
          socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n')
          socket.destroy()
          return
        }
      }
      this.wss.handleUpgrade(req, socket, head, (ws) => {
        this.wss.emit('connection', ws, req)
      })
    })

    this.wss.on('connection', (rawWs: WebSocket, req: IncomingMessage) => {
      const ws = rawWs as AliveSocket
      ws.missedPongs = 0
      ws.clientId = req.socket.remoteAddress ?? 'unknown'
      console.log(`[ws] Shield connected (${ws.clientId}), total=${this.clientCount}`)

      ws.on('pong', () => {
        ws.missedPongs = 0
      })

      ws.on('ping', () => {
        ws.missedPongs = 0
      })

      ws.on('message', (data) => {
        ws.missedPongs = 0
        try {
          const msg = JSON.parse(data.toString('utf8')) as {
            kind?: string
            showing?: boolean
            fullscreen?: boolean
          }
          if (msg.kind === 'overlay_state' && typeof msg.showing === 'boolean') {
            this.updateOverlayState({
              showing: msg.showing,
              fullscreen: Boolean(msg.fullscreen),
            })
          }
        } catch {
          // Ignore malformed client frames
        }
      })

      ws.on('close', () => {
        console.log(`[ws] Shield disconnected (${ws.clientId}), total=${this.clientCount}`)
        if (this.clientCount === 0) {
          this.updateOverlayState({ showing: false, fullscreen: false })
        }
      })

      ws.on('error', (err) => {
        console.warn(`[ws] Client error (${ws.clientId}):`, err.message)
      })

      ws.send(JSON.stringify({ kind: 'hello', server_ts_ms: Date.now() }))
    })

    // Tolerate up to 3 consecutive missed pongs (60 s) so bursty 4K Dolby Vision Wi-Fi traffic
    // on the Shield never causes false WebSocket resets.
    this.pingTimer = setInterval(() => {
      for (const rawWs of this.wss.clients) {
        const ws = rawWs as AliveSocket
        const missed = ws.missedPongs ?? 0
        if (missed >= 3) {
          console.warn(`[ws] Terminating unresponsive Shield socket (${ws.clientId}) after ${missed} missed pongs`)
          ws.terminate()
          continue
        }
        ws.missedPongs = missed + 1
        ws.ping()
      }
    }, 20_000)
    this.pingTimer.unref()
  }

  get overlayState(): OverlayLiveState {
    return this.currentOverlayState
  }

  onOverlayStateChange(listener: (state: OverlayLiveState) => void): void {
    this.stateListeners.add(listener)
    listener(this.currentOverlayState)
  }

  private updateOverlayState(next: OverlayLiveState): void {
    if (
      this.currentOverlayState.showing === next.showing &&
      this.currentOverlayState.fullscreen === next.fullscreen
    ) {
      return
    }
    this.currentOverlayState = next
    console.log(`[ws] Shield overlay state -> showing=${next.showing} fullscreen=${next.fullscreen}`)
    for (const listener of this.stateListeners) {
      try {
        listener(next)
      } catch {}
    }
  }

  get clientCount(): number {
    let count = 0
    for (const ws of this.wss.clients) {
      if (ws.readyState === WebSocket.OPEN) count++
    }
    return count
  }

  broadcastEvent(event: NormalizedRingEvent): number {
    const frame = JSON.stringify({
      kind: 'ring_event',
      relay_tx_ts_ms: Date.now(),
      event,
    })
    let delivered = 0
    for (const ws of this.wss.clients) {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(frame)
        delivered++
      }
    }
    return delivered
  }

  broadcastCommand(action: string): number {
    const frame = JSON.stringify({
      kind: 'overlay_command',
      relay_tx_ts_ms: Date.now(),
      action,
    })
    let delivered = 0
    for (const ws of this.wss.clients) {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(frame)
        delivered++
      }
    }
    return delivered
  }

  close(): void {
    clearInterval(this.pingTimer)
    this.wss.close()
  }
}
