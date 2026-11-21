import assert from 'node:assert/strict'
import http from 'node:http'
import test from 'node:test'
import { AlexaHueBridge } from './alexaHue.js'
import type { ShieldWsHub } from './wsHub.js'

test('AlexaHueBridge handles pairing, device discovery, and Shield command dispatch', async () => {
  const dispatched: string[] = []
  const fakeHub = {
    broadcastCommand(action: string) {
      dispatched.push(action)
      return 1
    },
  } as unknown as ShieldWsHub

  const bridge = new AlexaHueBridge({ port: 0, hostIp: '192.168.50.172', hub: fakeHub })
  const server = http.createServer((req, res) => {
    void bridge.handleHttp(req, res)
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const addr = server.address()
  assert.ok(addr && typeof addr === 'object')
  const base = `http://127.0.0.1:${addr.port}`

  try {
    // 1. UPnP description.xml
    const descRes = await fetch(`${base}/description.xml`)
    assert.equal(descRes.status, 200)
    const xml = await descRes.text()
    assert.match(xml, /Philips hue \(192\.168\.50\.172\)/)
    assert.match(xml, /929000226503/)

    // 2. Echo pairing POST /api
    const pairRes = await fetch(`${base}/api`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ devicetype: 'echo_alexa' }),
    })
    assert.equal(pairRes.status, 200)
    const pairJson = (await pairRes.json()) as Array<{ success?: { username?: string } }>
    const user = pairJson[0]?.success?.username
    assert.ok(user)

    // 3. Light discovery GET /api/<user>/lights
    const lightsRes = await fetch(`${base}/api/${user}/lights`)
    assert.equal(lightsRes.status, 200)
    const lights = (await lightsRes.json()) as Record<string, { name: string }>
    assert.equal(lights['1']?.name, 'Front Door View')
    assert.equal(lights['2']?.name, 'Front Door Fullscreen')

    // 4. Turn ON "Front Door View" -> Shield 'show'
    const on1 = await fetch(`${base}/api/${user}/lights/1/state`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ on: true }),
    })
    assert.equal(on1.status, 200)
    assert.deepEqual(dispatched, ['show'])

    // 5. Turn ON "Front Door Fullscreen" -> Shield 'fullscreen'
    const on2 = await fetch(`${base}/api/${user}/lights/2/state`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ on: true }),
    })
    assert.equal(on2.status, 200)
    assert.deepEqual(dispatched, ['show', 'fullscreen'])

    // 6. Turn OFF -> Shield 'dismiss'
    const off1 = await fetch(`${base}/api/${user}/lights/1/state`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ on: false }),
    })
    assert.equal(off1.status, 200)
    assert.deepEqual(dispatched, ['show', 'fullscreen', 'dismiss'])

    // 7. Two-way sync from Shield Pro updates Hue lights accurately
    bridge.syncOverlayState(true, true)
    let stateCheck = (await (await fetch(`${base}/api/${user}/lights`)).json()) as Record<
      string,
      { state: { on: boolean } }
    >
    assert.equal(stateCheck['1'].state.on, true)
    assert.equal(stateCheck['2'].state.on, true)

    bridge.syncOverlayState(true, false)
    stateCheck = (await (await fetch(`${base}/api/${user}/lights`)).json()) as Record<
      string,
      { state: { on: boolean } }
    >
    assert.equal(stateCheck['1'].state.on, true)
    assert.equal(stateCheck['2'].state.on, false)

    bridge.syncOverlayState(false, false)
    stateCheck = (await (await fetch(`${base}/api/${user}/lights`)).json()) as Record<
      string,
      { state: { on: boolean } }
    >
    assert.equal(stateCheck['1'].state.on, false)
    assert.equal(stateCheck['2'].state.on, false)
  } finally {
    bridge.stop()
    server.close()
  }
})
