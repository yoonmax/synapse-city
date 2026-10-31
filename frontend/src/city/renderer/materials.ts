import * as THREE from 'three'
import { cityUniforms } from './uniforms'

/**
 * Adds the intro "network drawing" reveal to a material: fragments beyond the reveal radius are
 * discarded and a thin cyan front glows where the reveal is advancing.
 */
export function withReveal<T extends THREE.Material>(material: T, glow = 0): T {
  material.onBeforeCompile = (shader) => {
    shader.uniforms.uReveal = cityUniforms.uReveal
    shader.uniforms.uRadius = cityUniforms.uRadius
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vRevealPos;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\nvRevealPos = (modelMatrix * vec4(transformed, 1.0)).xyz;')
    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vRevealPos;\nuniform float uReveal;\nuniform float uRadius;')
      .replace('#include <dithering_fragment>', `#include <dithering_fragment>
        float rd = length(vRevealPos.xz) / uRadius;
        float front = uReveal * 1.2;
        if (rd > front) discard;
        float edge = 1.0 - smoothstep(0.0, 0.06, front - rd);
        gl_FragColor.rgb += vec3(0.31, 0.82, 0.88) * edge * ${glow.toFixed(2)} * step(uReveal, 0.999);`)
  }
  material.customProgramCacheKey = () => `reveal-${glow}`
  return material
}
