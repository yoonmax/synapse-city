import { useEffect, useMemo, useRef } from 'react'
import * as THREE from 'three'
import { useFrame, type ThreeEvent } from '@react-three/fiber'
import type { CityMessage } from '../../network/protocol'
import { buildHeatGeometry, buildRoadGeometry, pickRoadOrNode } from '../geometry/roadGeometry'
import { withReveal } from './materials'
import { frames } from '../../state/frames'
import { useStore } from '../../state/store'
import { palette, toZ } from '../geometry/coords'

const LOW = new THREE.Color(palette.cyan)
const MID = new THREE.Color('#F2C14E')
const HIGH = new THREE.Color(palette.sodium)
const JAM = new THREE.Color(palette.danger)

/** Congestion ramp: cyan (flowing) through amber to red (jammed). */
function ramp(v: number, out: THREE.Color) {
  if (v < 0.33) return out.copy(LOW).lerp(MID, v / 0.33)
  if (v < 0.66) return out.copy(MID).lerp(HIGH, (v - 0.33) / 0.33)
  return out.copy(HIGH).lerp(JAM, (v - 0.66) / 0.34)
}

export function Roads({ city }: { city: CityMessage }) {
  const geo = useMemo(() => buildRoadGeometry(city), [city])
  const heat = useMemo(() => buildHeatGeometry(city), [city])
  const materials = useMemo(() => ({
    asphalt: withReveal(new THREE.MeshStandardMaterial({ color: palette.asphalt, roughness: 0.92, metalness: 0.05, side: THREE.DoubleSide }), 1.2),
    pavement: withReveal(new THREE.MeshStandardMaterial({ color: palette.pavement, roughness: 0.95, side: THREE.DoubleSide }), 0.4),
    markings: withReveal(new THREE.MeshStandardMaterial({
      vertexColors: true, roughness: 0.6, emissive: new THREE.Color('#8C9AAB'), emissiveIntensity: 0.9, side: THREE.DoubleSide,
      polygonOffset: true, polygonOffsetFactor: -2,
    }), 2.0),
    heat: new THREE.MeshBasicMaterial({
      vertexColors: true, transparent: true, opacity: 0.85, depthWrite: false, blending: THREE.AdditiveBlending,
      toneMapped: false, side: THREE.DoubleSide, polygonOffset: true, polygonOffsetFactor: -4,
    }),
  }), [])

  useEffect(() => () => {
    geo.asphalt.dispose(); geo.pavement.dispose(); geo.markings.dispose(); heat.dispose()
  }, [geo, heat])

  const lastVersion = useRef(-1)
  const tmp = useMemo(() => new THREE.Color(), [])
  const heatmapOn = useStore((s) => s.overlays.heatmap)
  const smoothed = useMemo(() => new Float32Array(city.segments.length), [city])

  useFrame(() => {
    const f = frames.cur
    if (!f || frames.version === lastVersion.current) return
    lastVersion.current = frames.version
    const seg = heat.getAttribute('segment') as THREE.BufferAttribute
    const col = heat.getAttribute('color') as THREE.BufferAttribute
    for (let s = 0; s < smoothed.length && s < f.congestion.length; s++) smoothed[s] = f.congestion[s] / 255
    for (let i = 0; i < seg.count; i++) {
      const v = smoothed[seg.getX(i)] ?? 0
      if (heatmapOn) {
        ramp(v, tmp).multiplyScalar(0.25 + v * 0.75)
      } else {
        // Ambient mode: only genuinely stressed roads glow, in sodium orange.
        const k = Math.max(0, (v - 0.45) / 0.55)
        tmp.copy(HIGH).multiplyScalar(k * 0.7)
      }
      col.setXYZ(i, tmp.r, tmp.g, tmp.b)
    }
    col.needsUpdate = true
  })

  const onClick = (e: ThreeEvent<MouseEvent>) => {
    e.stopPropagation()
    const p = e.point
    const hit = pickRoadOrNode(city, p.x, -p.z)
    const st = useStore.getState()
    if (hit.nodeDist < 2) st.selectNode(hit.node)
    else if (hit.roadDist < 3) st.selectRoad(hit.road)
  }

  return (
    <group>
      <mesh geometry={geo.pavement} material={materials.pavement} receiveShadow />
      <mesh geometry={geo.asphalt} material={materials.asphalt} receiveShadow onClick={onClick} />
      <mesh geometry={geo.markings} material={materials.markings} />
      <mesh geometry={heat} material={materials.heat} renderOrder={2} />
      <ClosedRoads city={city} />
    </group>
  )
}

/** Hatched barriers over closed roads. */
function ClosedRoads({ city }: { city: CityMessage }) {
  const closed = useStore((s) => s.sim?.closedRoads)
  const selectedRoad = useStore((s) => s.selectedRoad)
  const items = useMemo(() => (closed ?? []).map((id) => city.roads[id]).filter(Boolean), [closed, city])
  const sel = selectedRoad !== null ? city.roads[selectedRoad] : null
  return (
    <group>
      {items.map((r) => <RoadBand key={r.id} city={city} roadId={r.id} color={palette.danger} opacity={0.55} barrier />)}
      {sel && <RoadBand city={city} roadId={sel.id} color={palette.cyan} opacity={0.35} />}
    </group>
  )
}

function RoadBand({ city, roadId, color, opacity, barrier }: { city: CityMessage; roadId: number; color: string; opacity: number; barrier?: boolean }) {
  const r = city.roads[roadId]
  const a = city.nodes[r.a], b = city.nodes[r.b]
  const len = Math.hypot(b.x - a.x, b.y - a.y)
  const angle = Math.atan2(b.y - a.y, b.x - a.x)
  const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2
  const inner = len - a.r - b.r
  return (
    <group position={[mx, 0, toZ(my)]} rotation={[0, angle, 0]}>
      <mesh position={[0, 0.08, 0]} rotation={[-Math.PI / 2, 0, 0]} renderOrder={3}>
        <planeGeometry args={[inner, r.halfWidth * 2]} />
        <meshBasicMaterial color={color} transparent opacity={opacity} depthWrite={false} toneMapped={false} />
      </mesh>
      {barrier && [-1, 1].map((side) => (
        <group key={side} position={[side * (inner / 2 - 1.5), 0, 0]}>
          <mesh position={[0, 0.7, 0]} castShadow>
            <boxGeometry args={[0.5, 1.1, r.halfWidth * 1.8]} />
            <meshStandardMaterial color="#E8EDF2" emissive={palette.danger} emissiveIntensity={1.6} toneMapped={false} />
          </mesh>
        </group>
      ))}
    </group>
  )
}
