import { useEffect, useMemo } from 'react'
import * as THREE from 'three'
import type { CityMessage } from '../../network/protocol'
import { cityUniforms } from './uniforms'
import { toZ } from '../geometry/coords'

/** Style ordinals from the Java BuildingStyle enum. */
const TOWER = 0, SETBACK = 1, OFFICE = 2, APARTMENT = 3, HOUSE = 4, CIVIC = 5

interface Block {
  x: number; y: number; base: number; w: number; d: number; h: number; rot: number
  /** style, variant hash, distance-from-centre (0..1), window warmth */
  info: [number, number, number, number]
}

/** Expands each footprint into one or more stacked boxes according to its massing style. */
function massing(city: CityMessage): { blocks: Block[]; roofs: Block[] } {
  const blocks: Block[] = []
  const roofs: Block[] = []
  const b = city.buildings
  for (let i = 0; i < b.length; i += 9) {
    const [x, y, w, d, h, rot, style, , variant] = b.slice(i, i + 9)
    const dc = Math.min(1, Math.hypot(x, y) / city.radius)
    const warm = style === HOUSE || style === APARTMENT ? 1 : style === CIVIC ? 2 : 0
    const info = (s: number): [number, number, number, number] => [s, variant, dc, warm]
    switch (style) {
      case TOWER:
        blocks.push({ x, y, base: 0, w, d, h: h * 0.9, rot, info: info(style) })
        blocks.push({ x, y, base: h * 0.9, w: w * 0.62, d: d * 0.62, h: h * 0.1, rot, info: info(style) })
        break
      case SETBACK:
        blocks.push({ x, y, base: 0, w, d, h: h * 0.56, rot, info: info(style) })
        blocks.push({ x, y, base: h * 0.56, w: w * 0.78, d: d * 0.78, h: h * 0.3, rot, info: info(style) })
        blocks.push({ x, y, base: h * 0.86, w: w * 0.52, d: d * 0.52, h: h * 0.14, rot, info: info(style) })
        break
      case OFFICE:
        blocks.push({ x, y, base: 0, w, d, h, rot, info: info(style) })
        if ((variant & 3) === 0) blocks.push({ x, y, base: h, w: w * 0.32, d: d * 0.4, h: 2.6, rot, info: [9, variant, dc, 0] })
        break
      case HOUSE:
        blocks.push({ x, y, base: 0, w, d, h, rot, info: info(style) })
        roofs.push({ x, y, base: h, w: w * 1.04, d: d * 1.04, h: Math.min(4.5, w * 0.35), rot, info: info(style) })
        break
      default:
        blocks.push({ x, y, base: 0, w, d, h, rot, info: info(style) })
    }
  }
  return { blocks, roofs }
}

const vertexHead = /* glsl */ `
  attribute vec4 aInfo;
  attribute vec3 aSize;
  uniform float uRise;
  varying vec4 vInfo;
  varying vec3 vSize;
  varying vec3 vLocal;
  varying vec3 vObjNormal;
  varying float vWorldY;
`

const vertexBody = /* glsl */ `
  #include <begin_vertex>
  vInfo = aInfo;
  vSize = aSize;
  vLocal = position;
  vObjNormal = normal;
  // Staggered growth from the centre outward during the intro.
  float grow = clamp(uRise * 1.6 - aInfo.z * 0.6, 0.0, 1.0);
  grow = grow * grow * (3.0 - 2.0 * grow);
  transformed.y *= max(grow, 0.001);
`

const fragmentHead = /* glsl */ `
  uniform float uLights;
  varying vec4 vInfo;
  varying vec3 vSize;
  varying vec3 vLocal;
  varying vec3 vObjNormal;
  float hash21(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
`

/**
 * Procedural facade: a window grid in metres. Occupancy is decided per floor band first and then per
 * window, so lit windows cluster into floors and offices like real buildings instead of noise.
 */
const fragmentBody = /* glsl */ `
  #include <emissivemap_fragment>
  {
    float style = vInfo.x;
    float isRoof = step(0.5, vObjNormal.y);
    float u = abs(vObjNormal.x) > 0.5 ? (vLocal.z + 0.5) * vSize.z : (vLocal.x + 0.5) * vSize.x;
    float faceLen = abs(vObjNormal.x) > 0.5 ? vSize.z : vSize.x;
    float v = vLocal.y * vSize.y + vInfo.y * 0.0;
    bool tower = style < 1.5;
    float floorH = style == 4.0 ? 3.0 : (tower ? 3.8 : 3.4);
    float bayW = style == 4.0 ? 3.2 : (tower ? 1.9 : 3.4);
    vec2 cell = vec2(floor(u / bayW), floor(v / floorH));
    vec2 f = vec2(fract(u / bayW), fract(v / floorH));
    float mullion = tower ? 0.08 : 0.2;
    float pane = step(mullion, f.x) * step(f.x, 1.0 - mullion) * step(0.26, f.y) * step(f.y, 0.8);
    // Keep a solid margin at the corners of each face.
    pane *= step(1.2, u) * step(u, faceLen - 1.2);
    float floorSeed = hash21(vec2(cell.y * 0.37, vInfo.y * 0.011 + style * 3.1 + floor(abs(vObjNormal.x) + 0.5)));
    float floorLit = step(floorSeed, style == 2.0 ? 0.5 : 0.38);
    // Offices light runs of adjacent windows; homes light individual rooms.
    float runSeed = hash21(vec2(floor(cell.x / 3.0), cell.y) + vInfo.y * 0.0031);
    float windowSeed = hash21(cell + vec2(vInfo.y * 0.013, style));
    float on = floorLit * step(runSeed, 0.75) * step(windowSeed, 0.8);
    on += (1.0 - floorLit) * step(windowSeed, 0.07);
    on = min(on, 1.0);
    on *= step(windowSeed * 0.9 + floorSeed * 0.1, uLights * 1.02);
    float lit = pane * on * (1.0 - isRoof) * step(1.5, v) * step(style, 5.5) * step(style, 8.5);
    vec3 warm = vec3(1.0, 0.70, 0.40);
    vec3 cool = vec3(0.70, 0.88, 1.0);
    vec3 tint = vInfo.w > 1.5 ? vec3(1.0, 0.55, 0.22) : mix(cool, warm, vInfo.w);
    tint = mix(tint, warm, step(0.8, hash21(cell * 0.71 + vInfo.y * 0.017)) * 0.6);
    float level = 0.55 + 0.6 * hash21(cell * 1.7 + vInfo.y);
    totalEmissiveRadiance += tint * lit * level * 1.15;
    // Dark glazing reads as glass; spandrels keep the facade's base tone.
    diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.035, 0.05, 0.075), pane * (1.0 - isRoof) * (1.0 - on) * 0.85);
    // Floor slab lines give scale even where windows are dark.
    float slab = (1.0 - step(0.06, f.y)) * (1.0 - isRoof);
    diffuseColor.rgb *= 1.0 - slab * 0.35;
    // Lit crown on towers, sodium band on the civic station.
    float crown = step(vSize.y - 1.0, v) * (1.0 - isRoof) * step(style, 1.5);
    totalEmissiveRadiance += vec3(0.31, 0.82, 0.88) * crown * 0.9 * uLights;
    float civic = step(4.5, style) * step(style, 5.5) * step(vSize.y - 1.6, v) * (1.0 - isRoof);
    totalEmissiveRadiance += vec3(1.0, 0.6, 0.24) * civic * 2.2 * uLights;
    // Roofs catch the moonlight slightly; facades darken toward the street.
    diffuseColor.rgb *= mix(0.6, 1.0, clamp(v / 22.0, 0.0, 1.0));
    // Roofs: darker membrane with per-building variation so the skyline is not one flat tone.
    float roofTone = 0.42 + 0.22 * hash21(vec2(vInfo.y * 0.0071, style));
    diffuseColor.rgb = mix(diffuseColor.rgb, diffuseColor.rgb * roofTone + vec3(0.015, 0.016, 0.02), isRoof);
  }
`

function buildingMaterial(color: string) {
  const m = new THREE.MeshStandardMaterial({ color, roughness: 0.72, metalness: 0.08 })
  m.onBeforeCompile = (shader) => {
    shader.uniforms.uRise = cityUniforms.uRise
    shader.uniforms.uLights = cityUniforms.uLights
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\n' + vertexHead)
      .replace('#include <begin_vertex>', vertexBody)
    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', '#include <common>\n' + fragmentHead)
      .replace('#include <emissivemap_fragment>', fragmentBody)
  }
  return m
}

function roofMaterial() {
  const m = new THREE.MeshStandardMaterial({ color: '#2B2F38', roughness: 0.9, flatShading: true })
  m.onBeforeCompile = (shader) => {
    shader.uniforms.uRise = cityUniforms.uRise
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\nattribute vec4 aInfo;\nuniform float uRise;')
      .replace('#include <begin_vertex>', `#include <begin_vertex>
        float grow = clamp(uRise * 1.6 - aInfo.z * 0.6 - 0.15, 0.0, 1.0);
        transformed *= grow;`)
  }
  return m
}

function instanced(geometry: THREE.BufferGeometry, material: THREE.Material, blocks: Block[]) {
  const mesh = new THREE.InstancedMesh(geometry, material, Math.max(1, blocks.length))
  mesh.count = blocks.length
  const info = new Float32Array(blocks.length * 4)
  const size = new Float32Array(blocks.length * 3)
  const m = new THREE.Matrix4()
  const q = new THREE.Quaternion()
  const up = new THREE.Vector3(0, 1, 0)
  const color = new THREE.Color()
  blocks.forEach((b, i) => {
    q.setFromAxisAngle(up, b.rot)
    m.compose(new THREE.Vector3(b.x, b.base, toZ(b.y)), q, new THREE.Vector3(b.w, b.h, b.d))
    mesh.setMatrixAt(i, m)
    info.set(b.info, i * 4)
    size.set([b.w, b.h, b.d], i * 3)
    // Subtle per-building value variation keeps the massing from looking cloned.
    const k = 0.82 + ((b.info[1] % 97) / 97) * 0.3
    color.setRGB(k, k, k * 1.04)
    mesh.setColorAt(i, color)
  })
  geometry.setAttribute('aInfo', new THREE.InstancedBufferAttribute(info, 4))
  geometry.setAttribute('aSize', new THREE.InstancedBufferAttribute(size, 3))
  mesh.castShadow = true
  mesh.receiveShadow = true
  mesh.frustumCulled = false
  return mesh
}

export function Buildings({ city }: { city: CityMessage }) {
  const { body, roofs } = useMemo(() => {
    const { blocks, roofs } = massing(city)
    const box = new THREE.BoxGeometry(1, 1, 1)
    box.translate(0, 0.5, 0)
    const body = instanced(box, buildingMaterial('#59657A'), blocks)
    const prism = new THREE.CylinderGeometry(0.5, 0.5, 1, 3, 1)
    prism.rotateZ(Math.PI / 2)
    prism.rotateX(Math.PI / 6)
    prism.translate(0, 0.25, 0)
    prism.scale(1, 1, 1)
    const roofMesh = instanced(prism, roofMaterial(), roofs)
    return { body, roofs: roofMesh }
  }, [city])

  useEffect(() => () => {
    body.geometry.dispose(); (body.material as THREE.Material).dispose(); body.dispose()
    roofs.geometry.dispose(); (roofs.material as THREE.Material).dispose(); roofs.dispose()
  }, [body, roofs])

  return (
    <group>
      <primitive object={body} />
      <primitive object={roofs} />
    </group>
  )
}
