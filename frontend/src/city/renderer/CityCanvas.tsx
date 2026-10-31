import { Suspense, useEffect, useMemo } from 'react'
import * as THREE from 'three'
import { Canvas, useFrame, useThree } from '@react-three/fiber'
import { Bloom, EffectComposer, ToneMapping, Vignette } from '@react-three/postprocessing'
import { ToneMappingMode } from 'postprocessing'
import { useStore } from '../../state/store'
import { CameraRig, FOV, ISO_AZIMUTH, ISO_POLAR, cameraRig, framingDistance, overviewDistance, placeCamera } from '../camera/CameraRig'
import { Roads } from './Roads'
import { Buildings } from './Buildings'
import { Parks, Plinth, Signals, StreetLights, Trees } from './Landscape'
import { Vehicles } from '../vehicles/Vehicles'
import { Incidents, Landmarks, NodeMarker, RouteLine, SelectionMarker } from '../effects/Overlays'
import { PerfProbe } from './PerfProbe'
import { IntroDirector } from './IntroDirector'
import { cityUniforms } from './uniforms'
import { frames } from '../../state/frames'
import { palette } from '../geometry/coords'
import type { CityMessage } from '../../network/protocol'

function Lighting({ radius, shadows }: { radius: number; shadows: boolean }) {
  const light = useMemo(() => {
    const l = new THREE.DirectionalLight('#B4C6FF', 2.1)
    l.position.set(-radius * 0.7, radius * 1.1, radius * 0.45)
    l.castShadow = shadows
    l.shadow.mapSize.set(2048, 2048)
    const cam = l.shadow.camera as THREE.OrthographicCamera
    cam.left = -radius * 1.2; cam.right = radius * 1.2; cam.top = radius * 1.2; cam.bottom = -radius * 1.2
    cam.near = 10; cam.far = radius * 4
    l.shadow.bias = -0.0004
    l.shadow.normalBias = 0.6
    l.shadow.radius = 4
    return l
  }, [radius, shadows])
  return (
    <>
      <hemisphereLight args={['#3A4A66', '#0B0E14', 0.95]} />
      <ambientLight color="#1B2433" intensity={0.5} />
      <primitive object={light} />
      <primitive object={light.target} />
      {/* Warm bounce from the downtown core. */}
      <pointLight position={[0, 90, 0]} color="#FF9A3C" intensity={radius * 4} distance={radius * 0.9} decay={1.4} />
    </>
  )
}

function SceneSetup({ city }: { city: CityMessage }) {
  const scene = useThree((s) => s.scene)
  const camera = useThree((s) => s.camera) as THREE.PerspectiveCamera
  const gl = useThree((s) => s.gl)
  const advance = useThree((s) => s.advance)
  useEffect(() => {
    // Dev-only handle for measuring render cost and stepping frames from tooling.
    if (import.meta.env.DEV) (window as unknown as Record<string, unknown>).__synapse = { gl, scene, camera, store: useStore, advance, rig: cameraRig, frames }
  }, [gl, scene, camera, advance])
  useEffect(() => {
    scene.fog = new THREE.Fog('#0D1420', 400, 2400)
    cityUniforms.uRadius.value = city.radius
  }, [scene, city])
  // Initial overhead framing; the intro timeline animates from here.
  useEffect(() => {
    camera.fov = FOV.iso
    camera.near = 5
    camera.far = 30000
    camera.updateProjectionMatrix()
    const introDone = useStore.getState().introDone
    const target = new THREE.Vector3()
    if (introDone) {
      placeCamera(camera, target, overviewDistance(city.radius, FOV.iso, camera.aspect), ISO_POLAR, ISO_AZIMUTH)
      cameraRig.controls?.target.set(0, 0, 0)
      cameraRig.controls?.update()
    } else {
      placeCamera(camera, target, framingDistance(city.radius, FOV.iso, 1.7), 0.18, ISO_AZIMUTH - 0.9)
    }
  }, [camera, city])
  useFrame(({ clock }) => { cityUniforms.uTime.value = clock.elapsedTime })
  return null
}

export function CityCanvas() {
  const city = useStore((s) => s.city)
  const quality = useStore((s) => s.quality)
  const high = quality === 'high'
  const onMiss = () => {
    const st = useStore.getState()
    if (st.selectedVehicle !== null || st.selectedNode !== null || st.selectedRoad !== null) st.selectVehicle(null)
  }
  return (
    <Canvas
      className="!absolute inset-0"
      shadows={high ? { type: THREE.PCFSoftShadowMap } : false}
      dpr={high ? [1, 2] : [1, 1.25]}
      gl={{ antialias: !high, powerPreference: 'high-performance', alpha: false }}
      camera={{ fov: FOV.iso, near: 5, far: 30000, position: [0, 3000, 0] }}
      onPointerMissed={onMiss}
    >
      <color attach="background" args={[palette.basalt]} />
      {city && (
        <Suspense fallback={null}>
          <SceneSetup city={city} />
          <Lighting radius={city.radius} shadows={high} />
          <Plinth city={city} />
          <Roads city={city} />
          <Parks city={city} />
          <Trees city={city} />
          <Buildings city={city} />
          <StreetLights city={city} pools={high} />
          <Signals city={city} />
          <Landmarks city={city} />
          <Vehicles lowQuality={!high} />
          <Incidents />
          <RouteLine />
          <SelectionMarker />
          <NodeMarker city={city} />
          <CameraRig radius={city.radius} />
          <IntroDirector radius={city.radius} />
        </Suspense>
      )}
      <PerfProbe />
      {high && (
        <EffectComposer multisampling={4}>
          <Bloom mipmapBlur intensity={0.85} luminanceThreshold={0.85} luminanceSmoothing={0.25} radius={0.72} />
          <Vignette offset={0.28} darkness={0.72} />
          <ToneMapping mode={ToneMappingMode.ACES_FILMIC} />
        </EffectComposer>
      )}
    </Canvas>
  )
}
