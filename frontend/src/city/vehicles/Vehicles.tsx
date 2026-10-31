import { useEffect, useMemo } from 'react'
import * as THREE from 'three'
import { useFrame, type ThreeEvent } from '@react-three/fiber'
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js'
import { FLAG_BRAKING, FLAG_SIREN } from '../../network/protocol'
import { frameAlpha, frames } from '../../state/frames'
import { useStore } from '../../state/store'
import { radialTexture } from '../renderer/Landscape'
import { toZ } from '../geometry/coords'

const CAPACITY = 1500
/** length, height, width per VehicleType ordinal (CAR, TAXI, BUS, DELIVERY, EMERGENCY); matches Java. */
const DIMS: [number, number, number][] = [
  [4.4, 1.45, 1.85], [4.7, 1.5, 1.85], [11.5, 3.1, 2.55], [6.4, 2.6, 2.2], [5.4, 2.3, 2.05],
]
const CAR_COLORS = ['#B8C2CC', '#5D6B7A', '#2E3A48', '#8A97A6', '#D9DEE3', '#3F4B5A', '#6F5B4E', '#7E8C99']
const TYPE_COLOR = ['', '#F2B93B', '#2C8C99', '#D6D2C8', '#F4F6F8']

function carGeometry() {
  const body = new THREE.BoxGeometry(1, 0.5, 1)
  body.translate(0, 0.38, 0)
  const cabin = new THREE.BoxGeometry(0.52, 0.36, 0.84)
  cabin.translate(-0.06, 0.81, 0)
  return mergeGeometries([body, cabin])!
}

function boxVehicleGeometry() {
  const body = new THREE.BoxGeometry(1, 0.86, 1)
  body.translate(0, 0.53, 0)
  const roof = new THREE.BoxGeometry(0.96, 0.06, 0.92)
  roof.translate(0, 0.99, 0)
  return mergeGeometries([body, roof])!
}

/** Two lamps at one end of a unit vehicle (+x front, -x rear). */
function lampGeometry(front: boolean) {
  const x = front ? 0.505 : -0.505
  const parts = [-0.3, 0.3].map((z) => {
    const g = new THREE.BoxGeometry(0.02, 0.1, 0.2)
    g.translate(x, 0.45, z)
    return g
  })
  return mergeGeometries(parts)!
}

function linearTexture() {
  const c = document.createElement('canvas')
  c.width = 64; c.height = 8
  const g = c.getContext('2d')!
  const grad = g.createLinearGradient(0, 0, 64, 0)
  grad.addColorStop(0, 'rgba(255,255,255,0)')
  grad.addColorStop(1, 'rgba(255,255,255,1)')
  g.fillStyle = grad
  g.fillRect(0, 0, 64, 8)
  return new THREE.CanvasTexture(c)
}

export function Vehicles({ lowQuality }: { lowQuality: boolean }) {
  const meshes = useMemo(() => {
    const make = (geo: THREE.BufferGeometry, mat: THREE.Material) => {
      const m = new THREE.InstancedMesh(geo, mat, CAPACITY)
      m.count = 0
      m.frustumCulled = false
      // Allocate the colour buffer before first compile so instancing colour is in the shader.
      m.setColorAt(0, new THREE.Color(1, 1, 1))
      m.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
      return m
    }
    const paint = new THREE.MeshStandardMaterial({ roughness: 0.38, metalness: 0.55 })
    const cars = make(carGeometry(), paint)
    const boxes = make(boxVehicleGeometry(), paint.clone())
    cars.castShadow = boxes.castShadow = !lowQuality
    const headMat = new THREE.MeshBasicMaterial({ color: new THREE.Color('#FFF4DC').multiplyScalar(3), toneMapped: false })
    const tailMat = new THREE.MeshBasicMaterial({ color: '#FF2A1F', toneMapped: false })
    const heads = make(lampGeometry(true), headMat)
    const tails = make(lampGeometry(false), tailMat)
    const beamGeo = new THREE.PlaneGeometry(1, 1)
    beamGeo.rotateX(-Math.PI / 2)
    beamGeo.translate(0.5, 0.08, 0)
    const beams = make(beamGeo, new THREE.MeshBasicMaterial({
      map: radialTexture(), color: '#FFE6B8', transparent: true, opacity: 0.22, depthWrite: false,
      blending: THREE.AdditiveBlending, toneMapped: false,
    }))
    const trailGeo = new THREE.PlaneGeometry(1, 1)
    trailGeo.rotateX(-Math.PI / 2)
    trailGeo.translate(-0.5, 0.09, 0)
    const trails = make(trailGeo, new THREE.MeshBasicMaterial({
      map: linearTexture(), color: '#FF3B2E', transparent: true, opacity: 0.5, depthWrite: false,
      blending: THREE.AdditiveBlending, toneMapped: false,
    }))
    const sirenGeo = new THREE.BoxGeometry(0.22, 0.12, 0.7)
    sirenGeo.translate(0.1, 1.04, 0)
    const sirens = make(sirenGeo, new THREE.MeshBasicMaterial({ toneMapped: false }))
    return { cars, boxes, heads, tails, beams, trails, sirens }
  }, [lowQuality])

  useEffect(() => () => {
    for (const m of Object.values(meshes)) {
      m.geometry.dispose()
      ;(m.material as THREE.Material).dispose()
      m.dispose()
    }
  }, [meshes])

  /** Instance slot -> vehicle id, per pickable mesh. */
  const slots = useMemo(() => ({ cars: new Int32Array(CAPACITY), boxes: new Int32Array(CAPACITY) }), [])

  const tmp = useMemo(() => ({
    m: new THREE.Matrix4(), q: new THREE.Quaternion(), p: new THREE.Vector3(), s: new THREE.Vector3(),
    up: new THREE.Vector3(0, 1, 0), c: new THREE.Color(), red: new THREE.Color('#FF2A2A').multiplyScalar(3),
    blue: new THREE.Color('#3A7BFF').multiplyScalar(3),
  }), [])

  useFrame(({ clock }) => {
    const cur = frames.cur
    const { cars, boxes, heads, tails, beams, trails, sirens } = meshes
    if (!cur) {
      for (const m of Object.values(meshes)) m.count = 0
      return
    }
    const prev = frames.prev
    const a = frameAlpha(performance.now())
    const t = clock.elapsedTime
    let nc = 0, nb = 0, nl = 0, ns = 0, nt = 0
    for (let i = 0; i < cur.count; i++) {
      const id = cur.ids[i]
      let x = cur.x[i], y = cur.y[i], h = cur.heading[i]
      const j = prev ? frames.prevIndex.get(id) : undefined
      if (prev && j !== undefined) {
        x = prev.x[j] + (x - prev.x[j]) * a
        y = prev.y[j] + (y - prev.y[j]) * a
        let dh = h - prev.heading[j]
        if (dh > Math.PI) dh -= Math.PI * 2
        if (dh < -Math.PI) dh += Math.PI * 2
        h = prev.heading[j] + dh * a
      }
      const type = cur.type[i]
      const [L, H, W] = DIMS[type] ?? DIMS[0]
      const fade = cur.fade[i]
      const k = 0.35 + 0.65 * fade
      tmp.q.setFromAxisAngle(tmp.up, h)
      tmp.p.set(x, 0, toZ(y))
      tmp.s.set(L * k, H * k, W * k)
      tmp.m.compose(tmp.p, tmp.q, tmp.s)
      const boxy = type === 2 || type === 3
      const target = boxy ? boxes : cars
      const slot = boxy ? nb++ : nc++
      target.setMatrixAt(slot, tmp.m)
      ;(boxy ? slots.boxes : slots.cars)[slot] = id
      tmp.c.set(type === 0 ? CAR_COLORS[id % CAR_COLORS.length] : TYPE_COLOR[type])
      target.setColorAt(slot, tmp.c)

      heads.setMatrixAt(nl, tmp.m)
      tails.setMatrixAt(nl, tmp.m)
      const braking = (cur.flags[i] & FLAG_BRAKING) !== 0
      tmp.c.setScalar((braking ? 3.2 : 1.1) * fade)
      tails.setColorAt(nl, tmp.c)
      tmp.c.setScalar(fade)
      heads.setColorAt(nl, tmp.c)
      nl++

      if (!lowQuality) {
        const speed = cur.speed[i]
        tmp.s.set(10, 1, 4.2)
        tmp.p.set(x + Math.cos(h) * L * 0.5, 0, toZ(y + Math.sin(h) * L * 0.5))
        tmp.m.compose(tmp.p, tmp.q, tmp.s)
        beams.setMatrixAt(nt, tmp.m)
        const trail = Math.min(14, speed * 0.9)
        tmp.s.set(Math.max(0.01, trail), 1, W * 0.6)
        tmp.p.set(x - Math.cos(h) * L * 0.5, 0, toZ(y - Math.sin(h) * L * 0.5))
        tmp.m.compose(tmp.p, tmp.q, tmp.s)
        trails.setMatrixAt(nt, tmp.m)
        nt++
      }

      if (cur.flags[i] & FLAG_SIREN) {
        tmp.s.set(L, H, W)
        tmp.p.set(x, 0, toZ(y))
        tmp.m.compose(tmp.p, tmp.q, tmp.s)
        sirens.setMatrixAt(ns, tmp.m)
        sirens.setColorAt(ns, Math.floor(t * 6 + id) % 2 === 0 ? tmp.red : tmp.blue)
        ns++
      }
    }
    cars.count = nc; boxes.count = nb; heads.count = nl; tails.count = nl; sirens.count = ns
    beams.count = nt; trails.count = nt
    for (const m of [cars, boxes, heads, tails, beams, trails, sirens]) {
      m.instanceMatrix.needsUpdate = true
      if (m.instanceColor) m.instanceColor.needsUpdate = true
    }
  })

  const pick = (which: 'cars' | 'boxes') => (e: ThreeEvent<MouseEvent>) => {
    e.stopPropagation()
    if (e.instanceId === undefined) return
    useStore.getState().selectVehicle(slots[which][e.instanceId])
  }

  return (
    <group>
      <primitive object={meshes.cars} onClick={pick('cars')} />
      <primitive object={meshes.boxes} onClick={pick('boxes')} />
      <primitive object={meshes.heads} />
      <primitive object={meshes.tails} />
      {!lowQuality && <primitive object={meshes.beams} />}
      {!lowQuality && <primitive object={meshes.trails} />}
      <primitive object={meshes.sirens} />
    </group>
  )
}
