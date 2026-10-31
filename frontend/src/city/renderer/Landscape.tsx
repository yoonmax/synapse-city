import { useEffect, useMemo } from 'react'
import * as THREE from 'three'
import { useFrame } from '@react-three/fiber'
import type { CityMessage } from '../../network/protocol'
import { cityUniforms } from './uniforms'
import { palette, toZ } from '../geometry/coords'
import { frames } from '../../state/frames'

/** The diorama base: a bevelled slab the city sits on, with a hairline cyan rim. */
export function Plinth({ city }: { city: CityMessage }) {
  const size = city.radius * 2 + 110
  return (
    <group>
      <mesh position={[0, -4.01, 0]} receiveShadow>
        <boxGeometry args={[size, 8, size]} />
        <meshStandardMaterial color={palette.plinth} roughness={0.9} metalness={0.1} />
      </mesh>
      <mesh position={[0, -0.02, 0]} rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[size - 2, size - 2]} />
        <meshStandardMaterial color="#0E131A" roughness={1} />
      </mesh>
      <lineSegments position={[0, 0.02, 0]}>
        <edgesGeometry args={[new THREE.BoxGeometry(size + 0.4, 0.04, size + 0.4)]} />
        <lineBasicMaterial color={palette.cyan} transparent opacity={0.35} />
      </lineSegments>
      <mesh position={[0, -8.2, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <planeGeometry args={[size * 6, size * 6]} />
        <meshBasicMaterial color="#090C11" />
      </mesh>
    </group>
  )
}

export function Parks({ city }: { city: CityMessage }) {
  return (
    <group>
      {city.parks.map((p) => (
        <group key={p.id} position={[p.x, 0.04, toZ(p.y)]}>
          <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
            <circleGeometry args={[p.r, 48]} />
            <meshStandardMaterial color="#13261F" roughness={1} />
          </mesh>
          <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, 0.02, 0]}>
            <ringGeometry args={[p.r * 0.62 - 1.1, p.r * 0.62 + 1.1, 64]} />
            <meshStandardMaterial color="#2A2F33" roughness={1} />
          </mesh>
        </group>
      ))}
    </group>
  )
}

/** Instanced low-poly canopies; scale rises with the buildings during the intro. */
export function Trees({ city }: { city: CityMessage }) {
  const mesh = useMemo(() => {
    const geo = new THREE.IcosahedronGeometry(1, 0)
    geo.translate(0, 1.2, 0)
    const mat = new THREE.MeshStandardMaterial({ color: '#1E3B33', roughness: 0.85, flatShading: true })
    const n = city.trees.length / 2
    const m = new THREE.InstancedMesh(geo, mat, Math.max(1, n))
    m.count = n
    m.setColorAt(0, new THREE.Color(1, 1, 1))
    const mtx = new THREE.Matrix4()
    const c = new THREE.Color()
    for (let i = 0; i < n; i++) {
      const x = city.trees[i * 2], y = city.trees[i * 2 + 1]
      const s = 1.8 + ((i * 7919) % 100) / 100 * 1.6
      mtx.makeScale(s, s * (1.1 + ((i * 31) % 10) / 20), s).setPosition(x, 0, toZ(y))
      m.setMatrixAt(i, mtx)
      const k = 0.8 + ((i * 131) % 40) / 100
      m.setColorAt(i, c.setRGB(k * 0.9, k, k * 0.95))
    }
    m.castShadow = true
    m.receiveShadow = true
    m.frustumCulled = false
    return m
  }, [city])
  useEffect(() => () => { mesh.geometry.dispose(); (mesh.material as THREE.Material).dispose(); mesh.dispose() }, [mesh])
  useFrame(() => {
    const k = THREE.MathUtils.clamp(cityUniforms.uRise.value * 1.4 - 0.3, 0.001, 1)
    mesh.scale.setScalar(k)
  })
  return <primitive object={mesh} />
}

/** Lamp posts with sodium heads and additive light pools on the pavement. */
export function StreetLights({ city, pools }: { city: CityMessage; pools: boolean }) {
  const { posts, heads, glow } = useMemo(() => {
    const n = city.lights.length / 3
    const postGeo = new THREE.CylinderGeometry(0.09, 0.12, 6.5, 5)
    postGeo.translate(0, 3.25, 0)
    const posts = new THREE.InstancedMesh(postGeo, new THREE.MeshStandardMaterial({ color: '#39424F', roughness: 0.6, metalness: 0.4 }), Math.max(1, n))
    const headGeo = new THREE.BoxGeometry(1.2, 0.18, 0.42)
    headGeo.translate(0.55, 6.5, 0)
    const headMat = new THREE.MeshBasicMaterial({ color: new THREE.Color('#FFB866').multiplyScalar(2.2), toneMapped: false })
    const heads = new THREE.InstancedMesh(headGeo, headMat, Math.max(1, n))
    const glowGeo = new THREE.PlaneGeometry(1, 1)
    glowGeo.rotateX(-Math.PI / 2)
    const glowMat = new THREE.MeshBasicMaterial({
      map: radialTexture(), color: '#FFA24A', transparent: true, opacity: 0.5, depthWrite: false,
      blending: THREE.AdditiveBlending, toneMapped: false,
    })
    const glow = new THREE.InstancedMesh(glowGeo, glowMat, Math.max(1, n))
    const m = new THREE.Matrix4()
    const q = new THREE.Quaternion()
    const up = new THREE.Vector3(0, 1, 0)
    for (let i = 0; i < n; i++) {
      const x = city.lights[i * 3], y = city.lights[i * 3 + 1], rot = city.lights[i * 3 + 2]
      q.setFromAxisAngle(up, rot)
      m.compose(new THREE.Vector3(x, 0, toZ(y)), q, new THREE.Vector3(1, 1, 1))
      posts.setMatrixAt(i, m)
      heads.setMatrixAt(i, m)
      const px = x + Math.cos(rot) * 2.2, py = y + Math.sin(rot) * 2.2
      m.compose(new THREE.Vector3(px, 0.06, toZ(py)), q, new THREE.Vector3(19, 1, 19))
      glow.setMatrixAt(i, m)
    }
    for (const mesh of [posts, heads, glow]) { mesh.count = n; mesh.frustumCulled = false }
    posts.castShadow = true
    return { posts, heads, glow }
  }, [city])

  useEffect(() => () => {
    for (const mesh of [posts, heads, glow]) { mesh.geometry.dispose(); (mesh.material as THREE.Material).dispose(); mesh.dispose() }
  }, [posts, heads, glow])

  useFrame(() => {
    const l = cityUniforms.uLights.value
    ;(heads.material as THREE.MeshBasicMaterial).color.set('#FFB866').multiplyScalar(0.15 + 2.1 * l)
    ;(glow.material as THREE.MeshBasicMaterial).opacity = 0.5 * l
  })

  return (
    <group>
      <primitive object={posts} />
      <primitive object={heads} />
      {pools && <primitive object={glow} />}
    </group>
  )
}

let radial: THREE.Texture | null = null
export function radialTexture() {
  if (radial) return radial
  const c = document.createElement('canvas')
  c.width = c.height = 64
  const g = c.getContext('2d')!
  const grad = g.createRadialGradient(32, 32, 0, 32, 32, 32)
  grad.addColorStop(0, 'rgba(255,255,255,1)')
  grad.addColorStop(0.35, 'rgba(255,255,255,0.45)')
  grad.addColorStop(1, 'rgba(255,255,255,0)')
  g.fillStyle = grad
  g.fillRect(0, 0, 64, 64)
  radial = new THREE.CanvasTexture(c)
  return radial
}

/** Signal heads: one emissive lamp per approach, coloured from the binary frame. */
export function Signals({ city }: { city: CityMessage }) {
  const { poles, lamps, list } = useMemo(() => {
    const list = city.approaches.filter((a) => city.nodes[a.node].signalized)
    const poleGeo = new THREE.CylinderGeometry(0.1, 0.1, 4.2, 5)
    poleGeo.translate(0, 2.1, 0)
    const poles = new THREE.InstancedMesh(poleGeo, new THREE.MeshStandardMaterial({ color: '#2B323D', metalness: 0.5, roughness: 0.5 }), Math.max(1, list.length))
    const lampGeo = new THREE.SphereGeometry(0.42, 10, 8)
    lampGeo.translate(0, 4.5, 0)
    const lamps = new THREE.InstancedMesh(lampGeo, new THREE.MeshBasicMaterial({ toneMapped: false }), Math.max(1, list.length))
    const m = new THREE.Matrix4()
    const red = new THREE.Color('#FF4A3D').multiplyScalar(2)
    list.forEach((a, i) => {
      m.makeTranslation(a.x, 0, toZ(a.y))
      poles.setMatrixAt(i, m)
      lamps.setMatrixAt(i, m)
      lamps.setColorAt(i, red)
    })
    for (const mesh of [poles, lamps]) { mesh.count = list.length; mesh.frustumCulled = false }
    return { poles, lamps, list }
  }, [city])

  useEffect(() => () => {
    for (const mesh of [poles, lamps]) { mesh.geometry.dispose(); (mesh.material as THREE.Material).dispose(); mesh.dispose() }
  }, [poles, lamps])

  const colors = useMemo(() => [
    new THREE.Color('#FF4A3D').multiplyScalar(2.2),
    new THREE.Color('#FFB23D').multiplyScalar(2.4),
    new THREE.Color('#3DFFB0').multiplyScalar(2.2),
  ], [])
  useFrame(() => {
    const f = frames.cur
    if (!f) return
    list.forEach((a, i) => lamps.setColorAt(i, colors[f.colors[a.index] ?? 0]))
    if (lamps.instanceColor) lamps.instanceColor.needsUpdate = true
  })

  return (
    <group>
      <primitive object={poles} />
      <primitive object={lamps} />
    </group>
  )
}
