/**
 * Uniforms shared across city materials. The intro timeline animates these; skipping the intro
 * (or reduced motion) sets them straight to 1.
 */
export const cityUniforms = {
  uReveal: { value: 0 }, // road network drawn out to this fraction of the city radius
  uRise: { value: 0 },   // building growth
  uLights: { value: 0 }, // window and street lighting
  uRadius: { value: 500 },
  uTime: { value: 0 },
}

export function finishIntroUniforms() {
  cityUniforms.uReveal.value = 1
  cityUniforms.uRise.value = 1
  cityUniforms.uLights.value = 1
}
