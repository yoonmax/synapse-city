import { useEffect } from 'react'
import * as THREE from 'three'
import { useThree } from '@react-three/fiber'
import gsap from 'gsap'
import { useStore } from '../../state/store'
import { cityUniforms, finishIntroUniforms } from './uniforms'
import { FOV, ISO_AZIMUTH, ISO_POLAR, cameraRig, framingDistance, overviewDistance, placeCamera } from '../camera/CameraRig'

export const introControl = { skip: () => {} }

/**
 * The opening sequence, as one GSAP timeline: the Java road graph draws outward from the centre,
 * the camera descends from overhead to the isometric view, buildings rise, then windows and
 * street lamps switch on.
 */
export function IntroDirector({ radius }: { radius: number }) {
  const camera = useThree((s) => s.camera) as THREE.PerspectiveCamera
  useEffect(() => {
    if (useStore.getState().introDone) return
    const target = new THREE.Vector3()
    const rig = { d: framingDistance(radius, FOV.iso, 1.7), polar: 0.18, az: ISO_AZIMUTH - 0.9 }
    const final = { d: overviewDistance(radius, FOV.iso, camera.aspect), polar: ISO_POLAR, az: ISO_AZIMUTH }
    const apply = () => placeCamera(camera, target, rig.d, rig.polar, rig.az)
    let finished = false
    const finish = () => {
      if (finished) return
      finished = true
      finishIntroUniforms()
      Object.assign(rig, final)
      apply()
      cameraRig.locked = false
      const c = cameraRig.controls
      if (c) {
        c.enabled = true
        c.target.set(0, 0, 0)
        c.update()
      }
      useStore.setState({ introDone: true })
    }
    cameraRig.locked = true
    if (cameraRig.controls) cameraRig.controls.enabled = false
    cityUniforms.uReveal.value = 0
    cityUniforms.uRise.value = 0
    cityUniforms.uLights.value = 0
    apply()

    const skipParam = new URLSearchParams(location.search).get('intro') === 'skip'
    if (skipParam || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      finish()
      return
    }
    const tl = gsap.timeline({ onComplete: finish })
    tl.to(cityUniforms.uReveal, { value: 1, duration: 1.9, ease: 'power2.inOut' }, 0.35)
      .to(rig, { ...final, duration: 3.4, ease: 'power3.inOut', onUpdate: apply }, 0.25)
      .to(cityUniforms.uRise, { value: 1, duration: 1.9, ease: 'power2.out' }, 1.3)
      .to(cityUniforms.uLights, { value: 1, duration: 1.7, ease: 'power1.inOut' }, 2.3)
      .call(() => useStore.setState({ introDone: true }), [], 3.3)
    introControl.skip = () => { tl.kill(); finish() }
    return () => { tl.kill() }
  }, [camera, radius])
  return null
}
