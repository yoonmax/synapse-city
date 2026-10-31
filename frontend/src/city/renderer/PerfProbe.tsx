import { useRef } from 'react'
import { useFrame, useThree } from '@react-three/fiber'
import { useStore } from '../../state/store'

/** Measured client rendering stats, read by the performance panel. */
export const renderStats = { fps: 0, frameMs: 0, drawCalls: 0, triangles: 0, geometries: 0, textures: 0 }

/** Samples renderer cost and drops to low quality if the frame rate stays poor. */
export function PerfProbe() {
  const gl = useThree((s) => s.gl)
  const acc = useRef({ frames: 0, time: 0, slowSeconds: 0 })
  useFrame((_, dt) => {
    const a = acc.current
    a.frames++
    a.time += dt
    if (a.time >= 0.5) {
      renderStats.fps = Math.round(a.frames / a.time)
      renderStats.frameMs = Math.round((a.time / a.frames) * 1000 * 10) / 10
      renderStats.drawCalls = gl.info.render.calls
      renderStats.triangles = gl.info.render.triangles
      renderStats.geometries = gl.info.memory.geometries
      renderStats.textures = gl.info.memory.textures
      const st = useStore.getState()
      if (st.introDone && st.quality === 'high' && document.visibilityState === 'visible') {
        // Below ~8 fps the page is being throttled (background tab or hidden pane), not GPU-bound.
        const slow = renderStats.fps < 32 && renderStats.fps >= 8 && document.hasFocus()
        a.slowSeconds = slow ? a.slowSeconds + a.time : 0
        if (a.slowSeconds > 4) {
          st.set({ quality: 'low' })
          st.toast({ tone: 'info', title: 'Switched to performance mode', detail: `Rendering averaged ${renderStats.fps} fps; bloom, shadows and light pools are off. Re-enable in settings.` })
          a.slowSeconds = 0
        }
      }
      a.frames = 0
      a.time = 0
    }
  })
  return null
}
