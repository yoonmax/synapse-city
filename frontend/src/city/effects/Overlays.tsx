import { useMemo, useRef } from 'react'
import * as THREE from 'three'
import { useFrame } from '@react-three/fiber'
import { Line } from '@react-three/drei'
import { useStore } from '../../state/store'
import { vehiclePose } from '../../state/frames'
import { palette, toZ } from '../geometry/coords'
import type { CityMessage } from '../../network/protocol'

/** The selected vehicle's remaining Java-planned route, floating just above the street. */
export function RouteLine() {
  const vehicle = useStore((s) => s.vehicle)
  const showRoutes = useStore((s) => s.overlays.routes)
  const points = useMemo(() => {
    if (!vehicle || vehicle.routePoints.length < 4) return null
    const pts: THREE.Vector3[] = []
    for (let i = 0; i < vehicle.routePoints.length; i += 2) {
      const p = new THREE.Vector3(vehicle.routePoints[i], 0.9, toZ(vehicle.routePoints[i + 1]))
      if (!pts.length || pts[pts.length - 1].distanceTo(p) > 0.4) pts.push(p)
    }
    return pts.length >= 2 ? pts : null
  }, [vehicle])
  const dashRef = useRef<any>(null)
  useFrame((_, dt) => {
    if (dashRef.current?.material) dashRef.current.material.dashOffset -= dt * 18
  })
  if (!vehicle || !points || !showRoutes) return null
  const color = vehicle.siren ? palette.sodium : palette.cyan
  return (
    <group>
      <Line points={points} color={color} lineWidth={7} transparent opacity={0.18} toneMapped={false} />
      <Line points={points} color={color} lineWidth={2.4} toneMapped={false} />
      <Line ref={dashRef} points={points} color="#FFFFFF" lineWidth={2.2} dashed dashSize={4} gapSize={22} toneMapped={false} />
      <DestinationMarker x={vehicle.destX} y={vehicle.destY} color={color} />
    </group>
  )
}

function DestinationMarker({ x, y, color }: { x: number; y: number; color: string }) {
  const ring = useRef<THREE.Mesh>(null)
  useFrame(({ clock }) => {
    if (!ring.current) return
    const k = (clock.elapsedTime * 0.8) % 1
    ring.current.scale.setScalar(1 + k * 2.2)
    ;(ring.current.material as THREE.MeshBasicMaterial).opacity = 0.8 * (1 - k)
  })
  return (
    <group position={[x, 0.15, toZ(y)]}>
      <mesh rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[1.6, 2.1, 40]} />
        <meshBasicMaterial color={color} toneMapped={false} />
      </mesh>
      <mesh ref={ring} rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[2.0, 2.3, 40]} />
        <meshBasicMaterial color={color} transparent toneMapped={false} depthWrite={false} />
      </mesh>
      <mesh position={[0, 5, 0]}>
        <cylinderGeometry args={[0.05, 0.05, 10, 6]} />
        <meshBasicMaterial color={color} transparent opacity={0.6} toneMapped={false} />
      </mesh>
    </group>
  )
}

/** Ring that tracks the selected vehicle at render rate. */
export function SelectionMarker() {
  const selected = useStore((s) => s.selectedVehicle)
  const siren = useStore((s) => s.vehicle?.siren)
  const group = useRef<THREE.Group>(null)
  const pose = useMemo(() => [0, 0, 0], [])
  useFrame(({ clock }) => {
    const g = group.current
    if (!g || selected === null) return
    if (vehiclePose(selected, performance.now(), pose)) {
      g.visible = true
      g.position.set(pose[0], 0.12, toZ(pose[1]))
      g.rotation.y = pose[2]
      g.children[1].scale.setScalar(1 + 0.12 * Math.sin(clock.elapsedTime * 4))
    } else {
      g.visible = false
    }
  })
  if (selected === null) return null
  const color = siren ? palette.sodium : palette.cyan
  return (
    <group ref={group}>
      <mesh rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[4.2, 4.6, 48]} />
        <meshBasicMaterial color={color} toneMapped={false} />
      </mesh>
      <mesh rotation={[-Math.PI / 2, 0, 0]}>
        <circleGeometry args={[4.2, 48]} />
        <meshBasicMaterial color={color} transparent opacity={0.12} depthWrite={false} toneMapped={false} />
      </mesh>
    </group>
  )
}

/** Highlights the inspected junction box. */
export function NodeMarker({ city }: { city: CityMessage }) {
  const node = useStore((s) => s.selectedNode)
  const ref = useRef<THREE.Mesh>(null)
  useFrame(({ clock }) => {
    if (ref.current) ref.current.rotation.z = clock.elapsedTime * 0.4
  })
  if (node === null) return null
  const n = city.nodes[node]
  return (
    <group position={[n.x, 0.14, toZ(n.y)]}>
      <mesh ref={ref} rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[n.r + 3, n.r + 3.6, 6]} />
        <meshBasicMaterial color={palette.cyan} toneMapped={false} />
      </mesh>
    </group>
  )
}

/** Pulsing beacons at active incidents. */
export function Incidents() {
  const incidents = useStore((s) => s.sim?.incidents)
  return (
    <group>
      {(incidents ?? []).map((i) => <IncidentBeacon key={i.id} x={i.x} y={i.y} />)}
    </group>
  )
}

function IncidentBeacon({ x, y }: { x: number; y: number }) {
  const ring = useRef<THREE.Mesh>(null)
  const light = useRef<THREE.Mesh>(null)
  useFrame(({ clock }) => {
    const t = clock.elapsedTime
    const k = (t * 0.7) % 1
    if (ring.current) {
      ring.current.scale.setScalar(1 + k * 5)
      ;(ring.current.material as THREE.MeshBasicMaterial).opacity = 0.9 * (1 - k)
    }
    if (light.current) (light.current.material as THREE.MeshBasicMaterial).color.setScalar(0).add(new THREE.Color(palette.sodium).multiplyScalar(Math.sin(t * 9) > 0 ? 3 : 0.6))
  })
  return (
    <group position={[x, 0.2, toZ(y)]}>
      <mesh ref={ring} rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[2.4, 3, 40]} />
        <meshBasicMaterial color={palette.sodium} transparent toneMapped={false} depthWrite={false} />
      </mesh>
      <mesh position={[0, 1.1, 0]} rotation={[0, Math.PI / 4, 0]}>
        <coneGeometry args={[1.1, 2.2, 3]} />
        <meshStandardMaterial color="#F2F4F6" emissive={palette.sodium} emissiveIntensity={0.8} />
      </mesh>
      <mesh ref={light} position={[0, 3.2, 0]}>
        <sphereGeometry args={[0.45, 10, 8]} />
        <meshBasicMaterial toneMapped={false} />
      </mesh>
    </group>
  )
}

/** Bus stop shelters and the emergency station marker. */
export function Landmarks({ city }: { city: CityMessage }) {
  const station = city.nodes[city.stationNode]
  return (
    <group>
      {city.busStops.map((b) => (
        <group key={b.segment} position={[b.x, 0, toZ(b.y)]} rotation={[0, b.angle, 0]}>
          <mesh position={[0, 1.3, 0]} castShadow>
            <boxGeometry args={[4.5, 0.12, 1.4]} />
            <meshStandardMaterial color="#3A4655" />
          </mesh>
          <mesh position={[0, 0.7, 0.62]}>
            <boxGeometry args={[4.4, 1.3, 0.06]} />
            <meshBasicMaterial color={new THREE.Color(palette.cyan).multiplyScalar(0.9)} transparent opacity={0.55} toneMapped={false} />
          </mesh>
        </group>
      ))}
      <mesh position={[station.x, 0.13, toZ(station.y)]} rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[station.r + 2, station.r + 2.5, 4]} />
        <meshBasicMaterial color={palette.sodium} toneMapped={false} />
      </mesh>
    </group>
  )
}
