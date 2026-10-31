import type { Frame } from '../network/protocol'

/**
 * Non-reactive buffer of the two most recent authoritative snapshots. The renderer reads it every
 * animation frame and interpolates between them, so render rate is decoupled from network rate.
 */
export const frames = {
  prev: null as Frame | null,
  cur: null as Frame | null,
  prevIndex: new Map<number, number>(),
  curIndex: new Map<number, number>(),
  /** Smoothed wall-clock interval between frames, ms. */
  interval: 100,
  version: 0,
}

export function pushFrame(f: Frame) {
  if (frames.cur) {
    const dt = f.receivedAt - frames.cur.receivedAt
    if (dt > 0 && dt < 1000) frames.interval = frames.interval * 0.8 + dt * 0.2
  }
  frames.prev = frames.cur
  frames.prevIndex = frames.curIndex
  frames.cur = f
  const idx = new Map<number, number>()
  for (let i = 0; i < f.count; i++) idx.set(f.ids[i], i)
  frames.curIndex = idx
  frames.version++
}

export function resetFrames() {
  frames.prev = null
  frames.cur = null
  frames.prevIndex = new Map()
  frames.curIndex = new Map()
  frames.version++
}

/** Interpolation factor between prev and cur for the current render time. */
export function frameAlpha(now: number) {
  if (!frames.cur) return 1
  return Math.min(1, Math.max(0, (now - frames.cur.receivedAt) / frames.interval))
}

/** Interpolated world pose for a vehicle id, or null if absent. Writes into `out` = [x, y, heading]. */
export function vehiclePose(id: number, now: number, out: number[]): boolean {
  const cur = frames.cur
  if (!cur) return false
  const i = frames.curIndex.get(id)
  if (i === undefined) return false
  const prev = frames.prev
  const j = prev ? frames.prevIndex.get(id) : undefined
  if (!prev || j === undefined) {
    out[0] = cur.x[i]; out[1] = cur.y[i]; out[2] = cur.heading[i]
    return true
  }
  const a = frameAlpha(now)
  out[0] = prev.x[j] + (cur.x[i] - prev.x[j]) * a
  out[1] = prev.y[j] + (cur.y[i] - prev.y[j]) * a
  let dh = cur.heading[i] - prev.heading[j]
  if (dh > Math.PI) dh -= Math.PI * 2
  if (dh < -Math.PI) dh += Math.PI * 2
  out[2] = prev.heading[j] + dh * a
  return true
}
