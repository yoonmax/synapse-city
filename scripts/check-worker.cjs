// Smoke-tests the compiled browser build: drives the worker entry point in Node with every command
// and checks that each reply is well-formed. Usage: node scripts/check-worker.cjs <path to synapse-sim.js>
const fs = require('fs')
const vm = require('vm')
const assert = require('assert')

const texts = []
const frames = []
const self = {
  postMessage: (m) => (typeof m === 'string' ? texts.push(JSON.parse(m)) : frames.push(m)),
  onmessage: null,
}
const context = vm.createContext({ self, setInterval, setTimeout, console, performance, Int8Array, ArrayBuffer })
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), context)
self.main([])

const send = (cmd) => self.onmessage({ data: typeof cmd === 'string' ? cmd : JSON.stringify(cmd) })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const last = (type) => [...texts].reverse().find((m) => m.type === type)
const clear = () => { texts.length = 0 }

function decode(buf) {
  const dv = new DataView(buf)
  const count = dv.getUint16(2, true)
  const ids = []
  for (let i = 0; i < count; i++) ids.push(dv.getUint32(16 + i * 20, true))
  return { kind: dv.getUint8(0), count, segments: dv.getUint16(12, true), approaches: dv.getUint16(14, true), bytes: buf.byteLength, ids }
}

async function main() {
  assert.equal(texts[0].type, 'hello')
  assert.equal(last('scenarios').scenarios.length, 5)
  const city = last('city')
  assert.ok(city.nodes.length > 30 && city.buildings.length % 9 === 0)

  send({ type: 'SET_SPEED', value: 16 })
  await sleep(1500)
  assert.ok(frames.length > 5, 'frames flow')
  const frame = decode(frames[frames.length - 1])
  assert.equal(frame.kind, 1)
  assert.equal(frame.segments, city.segments.length)
  assert.equal(frame.approaches, city.approaches.length)
  assert.equal(frame.bytes, 16 + frame.count * 20 + frame.segments + frame.approaches)
  assert.ok(frame.count > 50, 'vehicles spawned')
  const state = last('state')
  assert.ok(state.time > 5 && state.metrics.active > 50, 'state reports progress')
  assert.ok(state.perf.steps > 50)

  send({ type: 'SELECT_VEHICLE', vehicleId: frame.ids[0] })
  const vehicle = last('vehicle')
  assert.equal(vehicle.id, frame.ids[0])
  assert.ok(vehicle.decisionText.length > 0 && vehicle.routePoints.length >= 0)
  send({ type: 'SELECT_VEHICLE', vehicleId: null })
  send({ type: 'INSPECT_INTERSECTION', nodeId: 5 })
  assert.equal(last('intersection').node, 5)
  send({ type: 'INSPECT_INTERSECTION', nodeId: null })

  clear()
  for (const cmd of [
    { type: 'SET_DENSITY', value: 200 }, { type: 'SET_DEMAND', profile: 'COMMUTE' },
    { type: 'ADD_VEHICLES', count: 5, vehicleType: 'TAXI' }, { type: 'CLOSE_ROAD', roadId: 4 }, { type: 'OPEN_ROAD', roadId: 4 },
    { type: 'CREATE_INCIDENT', roadId: null }, { type: 'DISPATCH_EMERGENCY', roadId: null },
    { type: 'SET_SIGNAL_MODE', mode: 'FIXED' }, { type: 'SET_ROUTING', algorithm: 'DIJKSTRA' }, { type: 'PAUSE' }, { type: 'STEP', ticks: 10 }, { type: 'RESUME' },
  ]) send(cmd)
  assert.equal(texts.filter((m) => m.type === 'error').length, 0, JSON.stringify(texts.filter((m) => m.type === 'error')))
  await sleep(700)
  const after = last('state')
  assert.equal(after.signalMode, 'FIXED')
  assert.equal(after.routing, 'DIJKSTRA')
  assert.equal(after.targetVehicles, 200)
  const seen = texts.filter((m) => m.type === 'state').flatMap((m) => m.events).map((e) => e.type)
  for (const type of ['INCIDENT_CREATED', 'EMERGENCY_DISPATCHED', 'ROAD_CLOSED', 'SIGNAL_MODE_CHANGED']) {
    assert.ok(seen.includes(type), `event ${type} delivered`)
  }

  clear()
  for (const bad of ['not json', '{"type":"SET_SPEED","value":3}', '{"type":"CLOSE_ROAD","roadId":99999}', '{"type":"NOPE"}']) send(bad)
  assert.equal(texts.filter((m) => m.type === 'error').length, 4, 'bad commands are rejected, not thrown')

  send({ type: 'LOAD_SCENARIO', scenario: 'EMERGENCY' })
  assert.equal(last('city').layout, 'RADIAL')
  await sleep(600)
  assert.equal(last('state').scenario, 'EMERGENCY')
  send({ type: 'REGENERATE', seed: 4242, layout: 'ORGANIC' })
  assert.equal(last('city').seed, 4242)
  send({ type: 'RESET' })

  clear()
  const t0 = Date.now()
  send({ type: 'RUN_COMPARISON', durationSeconds: 600 })
  while (!last('comparison') || last('comparison').status === 'running') await sleep(100)
  const done = last('comparison')
  assert.equal(done.status, 'done', done.message)
  console.log(`comparison: ${Date.now() - t0} ms; fixed ${done.result.fixed.completedTrips} trips, adaptive ${done.result.adaptive.completedTrips} trips`)
  assert.ok(done.result.fixed.series.length > 100)

  const avg = last('state')?.perf
  console.log('all commands ok', avg ? `(avg step ${avg.avgStepMicros.toFixed(0)} us)` : '')
  process.exit(0)
}
main().catch((e) => { console.error('FAILED:', e.stack); process.exit(1) })
