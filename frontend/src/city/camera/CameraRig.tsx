import { useEffect, useRef } from 'react'
import * as THREE from 'three'
import { useFrame, useThree } from '@react-three/fiber'
import { OrbitControls } from '@react-three/drei'
import type { OrbitControls as OrbitControlsImpl } from 'three-stdlib'
import gsap from 'gsap'
import { useStore } from '../../state/store'
import { vehiclePose } from '../../state/frames'
import { toZ } from '../geometry/coords'

/** Field of view per camera mode. "Iso" is a long telephoto lens that approximates an axonometric view. */
export const FOV = { iso: 11, perspective: 42 }
export const ISO_POLAR = 0.84
export const ISO_AZIMUTH = Math.PI * 0.25

/** Distance at which the city (radius r) fills the view for a given fov. */
export function framingDistance(radius: number, fov: number, fill = 1.25) {
  return (radius * fill) / Math.tan(THREE.MathUtils.degToRad(fov) / 2)
}

/** Overview distance that keeps the whole city in frame on portrait screens too. */
export function overviewDistance(radius: number, fov: number, aspect: number) {
  return framingDistance(radius, fov, 0.78) * Math.max(1, 1.15 / Math.max(aspect, 0.3))
}

export const cameraRig = {
  controls: null as OrbitControlsImpl | null,
  /** Set while the intro owns the camera. */
  locked: true,
}

export function placeCamera(camera: THREE.Camera, target: THREE.Vector3, distance: number, polar: number, azimuth: number) {
  const s = new THREE.Spherical(distance, polar, azimuth)
  camera.position.setFromSpherical(s).add(target)
  camera.lookAt(target)
}

export function CameraRig({ radius }: { radius: number }) {
  const controls = useRef<OrbitControlsImpl>(null)
  const camera = useThree((s) => s.camera) as THREE.PerspectiveCamera
  const scene = useThree((s) => s.scene)
  const mode = useStore((s) => s.cameraMode)
  const focusRequest = useStore((s) => s.focusRequest)
  const follow = useStore((s) => s.follow)
  const selected = useStore((s) => s.selectedVehicle)
  const pose = useRef([0, 0, 0])

  useEffect(() => {
    cameraRig.controls = controls.current
  })

  // Mode switch: tween the lens while preserving framing.
  const first = useRef(true)
  useEffect(() => {
    if (first.current) { first.current = false; return }
    const c = controls.current
    if (!c) return
    const target = c.target.clone()
    const offset = camera.position.clone().sub(target)
    const fromFov = camera.fov
    const toFov = FOV[mode]
    const ratio = Math.tan(THREE.MathUtils.degToRad(fromFov) / 2) / Math.tan(THREE.MathUtils.degToRad(toFov) / 2)
    const fromLen = offset.length()
    const toLen = fromLen * ratio
    const state = { t: 0 }
    gsap.to(state, {
      t: 1, duration: 0.9, ease: 'power3.inOut',
      onUpdate: () => {
        const tanF = THREE.MathUtils.lerp(Math.tan(THREE.MathUtils.degToRad(fromFov) / 2), Math.tan(THREE.MathUtils.degToRad(toFov) / 2), state.t)
        camera.fov = THREE.MathUtils.radToDeg(Math.atan(tanF) * 2)
        const len = fromLen * Math.tan(THREE.MathUtils.degToRad(fromFov) / 2) / tanF
        camera.position.copy(target).add(offset.clone().setLength(len))
        camera.updateProjectionMatrix()
        c.update()
      },
    })
    void toLen
  }, [mode, camera])

  // Optional deep link: ?cam=x,y,fov frames a point in simulation coordinates (used for screenshots).
  const introDone = useStore((s) => s.introDone)
  useEffect(() => {
    const c = controls.current
    const raw = new URLSearchParams(location.search).get('cam')
    if (!introDone || !c || !raw) return
    const [x, y, fov] = raw.split(',').map(Number)
    if (![x, y].every(Number.isFinite)) return
    const offset = camera.position.clone().sub(c.target)
    c.target.set(x, 0, toZ(y))
    camera.position.copy(c.target).add(offset)
    if (Number.isFinite(fov)) {
      camera.fov = fov
      camera.updateProjectionMatrix()
    }
    c.update()
  }, [introDone, camera])

  // Focus requests from the UI (events, inspector, keyboard).
  useEffect(() => {
    const c = controls.current
    if (!focusRequest || !c) return
    const dest = new THREE.Vector3(focusRequest.x, 0, toZ(focusRequest.y))
    const offset = camera.position.clone().sub(c.target)
    const wanted = framingDistance(radius, camera.fov, 0.32 / focusRequest.zoom)
    const len = Math.min(offset.length(), wanted)
    const fromT = c.target.clone()
    const fromOff = offset.clone()
    const state = { t: 0 }
    gsap.to(state, {
      t: 1, duration: 1.1, ease: 'power3.inOut',
      onUpdate: () => {
        c.target.lerpVectors(fromT, dest, state.t)
        const l = THREE.MathUtils.lerp(fromOff.length(), len, state.t)
        camera.position.copy(c.target).add(fromOff.clone().setLength(l))
        c.update()
      },
    })
  }, [focusRequest, camera, radius])

  useFrame(() => {
    const c = controls.current
    if (!c) return
    if (follow && selected !== null && !cameraRig.locked && vehiclePose(selected, performance.now(), pose.current)) {
      const dest = new THREE.Vector3(pose.current[0], 0, toZ(pose.current[1]))
      const delta = dest.sub(c.target).multiplyScalar(0.12)
      c.target.add(delta)
      camera.position.add(delta)
    }
    // Depth-based haze relative to the current viewing distance.
    const dist = camera.position.distanceTo(c.target)
    const fog = scene.fog as THREE.Fog | null
    if (fog) {
      fog.near = dist - radius * 0.35
      fog.far = dist + radius * 2.6
    }
  })

  return (
    <OrbitControls
      ref={controls}
      makeDefault
      enableDamping
      dampingFactor={0.08}
      enabled={!cameraRig.locked}
      maxPolarAngle={1.32}
      minPolarAngle={0.12}
      minDistance={40}
      maxDistance={framingDistance(radius, FOV.iso, 3.2)}
      zoomSpeed={0.9}
      rotateSpeed={0.55}
      screenSpacePanning={false}
      touches={{ ONE: THREE.TOUCH.ROTATE, TWO: THREE.TOUCH.DOLLY_PAN }}
    />
  )
}
