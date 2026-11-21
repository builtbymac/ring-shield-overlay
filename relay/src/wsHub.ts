import type { IncomingMessage } from 'node:http'
import { WebSocketServer, WebSocket } from 'ws'
import type { Server } from 'node:http'
import type { NormalizedRingEvent } from './webhook.js'

export type OverlayCommandAction = 'show' | 'fullscreen' | 'toggle_fullscreen' | 'dismiss'

interface AliveSocket extends WebSocket {
  isAlive?: boolean
  clientId?: string
}

/**
 * Ultra-low-latency LAN WebSocket broadcast hub for Shield Pro clients.
 * Keeps TCP sockets warm with 20 s WebSocket pings so notifications arrive in <1 ms.
 */
export class ShieldWsHub {
  private readonly wss: WebSocketServer
  private readonly pingTimer: NodeJS.Timeout

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
      ws.isAlive = true
      ws.clientId = req.socket.remoteAddress ?? 'unknown'
      console.log(`[ws] Shield connected (${ws.clientId}), total=${this.clientCount}`)

      ws.on('pong', () => {
        ws.isAlive = true
      })

      ws.on('close', () => {
        console.log(`[ws] Shield disconnected (${ws.clientId}), total=${this.clientCount}`)
      })

      ws.on('error', (err) => {
        console.warn(`[ws] Client error (${ws.clientId}):`, err.message)
      })

      ws.send(JSON.stringify({ kind: 'hello', server_ts_ms: Date.now() }))
    })

    this.pingTimer = setInterval(() => {
      for (const rawWs of this.wss.clients) {
        const ws = rawWs as AliveSocket
        if (ws.isAlive === false) {
          ws.terminate()
          continue
        }
        ws.isAlive = false
        ws.ping()
      }
    }, 20_000)
    this.pingTimer.unref()
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
