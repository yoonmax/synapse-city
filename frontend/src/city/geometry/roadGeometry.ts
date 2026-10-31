import * as THREE from 'three'
import type { CityMessage, RoadDto } from '../../network/protocol'
import { toZ } from './coords'

/** Flat geometry accumulator: quads in the ground plane at a fixed height. */
class FlatBuilder {
  pos: number[] = []
  col: number[] = []
  extra: number[] = []
  idx: number[] = []

  /** Quad given four (x, y) simulation-plane corners in CCW order. */
  quad(c: [number, number][], y: number, color?: THREE.Color, tag = 0) {
    const base = this.pos.length / 3
    for (const [x, sy] of c) {
      this.pos.push(x, y, toZ(sy))
      if (color) this.col.push(color.r, color.g, color.b)
      this.extra.push(tag)
    }
    this.idx.push(base, base + 2, base + 1, base, base + 3, base + 2)
  }

  /** Rectangle along a line from a to b with half-width hw, offset laterally by `off`. */
  strip(ax: number, ay: number, bx: number, by: number, hw: number, off: number, y: number, color?: THREE.Color, tag = 0) {
    const dx = bx - ax, dy = by - ay
    const len = Math.hypot(dx, dy) || 1
    const ux = dx / len, uy = dy / len
    const rx = uy, ry = -ux // right normal
    const o1 = off - hw, o2 = off + hw
    this.quad([
      [ax + rx * o1, ay + ry * o1],
      [bx + rx * o1, by + ry * o1],
      [bx + rx * o2, by + ry * o2],
      [ax + rx * o2, ay + ry * o2],
    ], y, color, tag)
  }

  disc(cx: number, cy: number, r: number, y: number, segments = 20) {
    const base = this.pos.length / 3
    this.pos.push(cx, y, toZ(cy))
    this.extra.push(0)
    for (let i = 0; i <= segments; i++) {
      const a = (i / segments) * Math.PI * 2
      this.pos.push(cx + Math.cos(a) * r, y, toZ(cy + Math.sin(a) * r))
      this.extra.push(0)
    }
    for (let i = 1; i <= segments; i++) this.idx.push(base, base + i, base + i + 1)
  }

  build(withColor = false, extraName?: string) {
    const g = new THREE.BufferGeometry()
    g.setAttribute('position', new THREE.Float32BufferAttribute(this.pos, 3))
    if (withColor) g.setAttribute('color', new THREE.Float32BufferAttribute(this.col, 3))
    if (extraName) g.setAttribute(extraName, new THREE.Float32BufferAttribute(this.extra, 1))
    g.setIndex(this.idx)
    g.computeVertexNormals()
    g.computeBoundingSphere()
    return g
  }
}

const nodePos = (city: CityMessage, id: number) => city.nodes[id]

/** Builds asphalt, pavement and road-marking geometry for the whole network. */
export function buildRoadGeometry(city: CityMessage) {
  const asphalt = new FlatBuilder()
  const pavement = new FlatBuilder()
  const marks = new FlatBuilder()
  const white = new THREE.Color('#C9D3DE')
  const amber = new THREE.Color('#E8B26A')

  for (const r of city.roads) {
    const a = nodePos(city, r.a), b = nodePos(city, r.b)
    asphalt.strip(a.x, a.y, b.x, b.y, r.halfWidth, 0, 0.02)
    pavement.strip(a.x, a.y, b.x, b.y, r.halfWidth + 2.8, 0, 0.0)
    roadMarkings(marks, r, a, b, white, amber)
  }
  for (const n of city.nodes) {
    let maxHw = 0
    for (const r of city.roads) if (r.a === n.id || r.b === n.id) maxHw = Math.max(maxHw, r.halfWidth)
    asphalt.disc(n.x, n.y, Math.max(maxHw, n.r - 0.8), 0.021)
    pavement.disc(n.x, n.y, maxHw + 3.6, 0.001)
  }
  // Stop lines and zebra crossings at signalised approaches.
  for (const ap of city.approaches) {
    const node = city.nodes[ap.node]
    if (!node.signalized) continue
    const seg = city.segments[ap.segment]
    const road = city.roads[seg.road]
    const dx = seg.ex - seg.sx, dy = seg.ey - seg.sy
    const len = Math.hypot(dx, dy) || 1
    const ux = dx / len, uy = dy / len
    const med = road.median / 2
    const laneSpan = road.halfWidth - med
    // Stop line across this direction's lanes, just before the junction box.
    const sx = seg.ex - ux * 0.6, sy = seg.ey - uy * 0.6
    marks.strip(sx - ux * 0.25, sy - uy * 0.25, sx + ux * 0.25, sy + uy * 0.25, laneSpan / 2, med + laneSpan / 2, 0.035, white)
    // Zebra stripes spanning the full carriageway, in the box mouth.
    const cx = seg.ex + ux * 1.6, cy = seg.ey + uy * 1.6
    const stripes = Math.floor((road.halfWidth * 2) / 1.1)
    for (let i = 0; i < stripes; i++) {
      const o = -road.halfWidth + 0.55 + i * 1.1
      if (o > road.halfWidth - 0.3) break
      marks.strip(cx - ux * 1.3, cy - uy * 1.3, cx + ux * 1.3, cy + uy * 1.3, 0.28, o, 0.035, white)
    }
  }
  return {
    asphalt: asphalt.build(),
    pavement: pavement.build(),
    markings: marks.build(true),
  }
}

function roadMarkings(m: FlatBuilder, r: RoadDto, a: { x: number; y: number; r: number }, b: { x: number; y: number; r: number },
  white: THREE.Color, amber: THREE.Color) {
  const dx = b.x - a.x, dy = b.y - a.y
  const len = Math.hypot(dx, dy)
  if (len < 1) return
  const ux = dx / len, uy = dy / len
  const t0 = a.r + 2.5, t1 = len - b.r - 2.5
  if (t1 <= t0) return
  const at = (t: number) => [a.x + ux * t, a.y + uy * t] as const
  const solid = (off: number, hw: number, color: THREE.Color) => {
    const [x0, y0] = at(t0), [x1, y1] = at(t1)
    m.strip(x0, y0, x1, y1, hw, off, 0.03, color)
  }
  const dashed = (off: number, hw: number, color: THREE.Color, dash = 3, gap = 4.5) => {
    for (let t = t0; t < t1; t += dash + gap) {
      const [x0, y0] = at(t), [x1, y1] = at(Math.min(t + dash, t1))
      m.strip(x0, y0, x1, y1, hw, off, 0.03, color)
    }
  }
  const edge = r.halfWidth - 0.35
  solid(edge, 0.08, white)
  solid(-edge, 0.08, white)
  if (r.cls === 'ARTERIAL') {
    solid(r.median / 2 - 0.12, 0.07, amber)
    solid(-r.median / 2 + 0.12, 0.07, amber)
    const lane = (r.halfWidth - r.median / 2) / r.lanes
    for (let i = 1; i < r.lanes; i++) {
      dashed(r.median / 2 + lane * i, 0.07, white)
      dashed(-(r.median / 2 + lane * i), 0.07, white)
    }
  } else if (r.cls === 'COLLECTOR') {
    solid(0, 0.08, amber)
  } else {
    dashed(0, 0.07, white, 2.2, 3.8)
  }
}

/**
 * Heatmap strips: one per directed segment, covering that direction's lanes. Each vertex carries
 * the segment index so colours can be updated from binary frames without rebuilding geometry.
 */
export function buildHeatGeometry(city: CityMessage) {
  const b = new FlatBuilder()
  for (const s of city.segments) {
    const road = city.roads[s.road]
    const med = road.median / 2
    const span = road.halfWidth - med
    b.strip(s.sx, s.sy, s.ex, s.ey, span / 2 - 0.2, med + span / 2, 0.05, new THREE.Color(0, 0, 0), s.id)
  }
  return b.build(true, 'segment')
}

/** Nearest road (by centreline distance) and nearest node to a simulation-plane point. */
export function pickRoadOrNode(city: CityMessage, x: number, y: number) {
  let bestRoad = -1, bestRoadD = Infinity
  for (const r of city.roads) {
    const a = city.nodes[r.a], b = city.nodes[r.b]
    const d = pointSegDist(x, y, a.x, a.y, b.x, b.y) - r.halfWidth
    if (d < bestRoadD) { bestRoadD = d; bestRoad = r.id }
  }
  let bestNode = -1, bestNodeD = Infinity
  for (const n of city.nodes) {
    const d = Math.hypot(n.x - x, n.y - y) - n.r
    if (d < bestNodeD) { bestNodeD = d; bestNode = n.id }
  }
  return { road: bestRoad, roadDist: bestRoadD, node: bestNode, nodeDist: bestNodeD }
}

function pointSegDist(px: number, py: number, ax: number, ay: number, bx: number, by: number) {
  const dx = bx - ax, dy = by - ay
  const l2 = dx * dx + dy * dy
  const t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2))
  return Math.hypot(px - (ax + dx * t), py - (ay + dy * t))
}
