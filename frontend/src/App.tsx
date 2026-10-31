import { useEffect } from 'react'
import { CityCanvas } from './city/renderer/CityCanvas'
import { connect } from './network/client'
import { useStore } from './state/store'
import { LeftPanel } from './ui/controls/LeftPanel'
import { RightPanel } from './ui/inspector/RightPanel'
import { BottomBar, HelpDialog, PerfPanel, Toasts, TopBar, useShortcuts } from './ui/Chrome'
import { Intro } from './ui/Intro'

const INSTANT = new URLSearchParams(location.search).get('intro') === 'skip'

export default function App() {
  const introDone = useStore((s) => s.introDone)
  const leftOpen = useStore((s) => s.leftOpen)
  const rightOpen = useStore((s) => s.rightOpen)
  const set = useStore((s) => s.set)
  useEffect(() => connect(), [])
  useEffect(() => {
    // View options for demos and screenshot capture: ?panels=0&heat=1
    const q = new URLSearchParams(location.search)
    if (q.get('panels') === '0') set({ leftOpen: false, rightOpen: false })
    if (q.get('heat') === '1') set({ overlays: { ...useStore.getState().overlays, heatmap: true } })
  }, [set])
  useShortcuts()

  const enter = (delay: number) => (INSTANT ? { transitionDuration: '0ms' } : { transitionDelay: introDone ? `${delay}ms` : '0ms' })
  return (
    <div className="relative h-full w-full select-none overflow-hidden">
      <CityCanvas />
      <Intro />
      <div className="pointer-events-none absolute inset-0 z-10 flex flex-col gap-3 p-3">
        <div className={`transition-[opacity,transform] duration-700 ease-out-quint ${introDone ? 'opacity-100' : '-translate-y-3 opacity-0'}`} style={enter(0)}>
          <TopBar />
        </div>
        <div className="relative flex min-h-0 flex-1 gap-3">
          <aside
            aria-label="Simulation controls"
            className={`panel pointer-events-auto absolute inset-y-0 left-0 z-10 w-[300px] max-w-[calc(100vw-24px)] overflow-hidden rounded-[10px] transition-[opacity,transform] duration-500 ease-out-quint md:static
              ${introDone && leftOpen ? 'translate-x-0 opacity-100' : 'pointer-events-none -translate-x-[110%] opacity-0'}`}
            style={enter(120)}
          >
            <LeftPanel />
          </aside>
          <div className="pointer-events-none relative flex-1">
            <div className="absolute inset-x-0 top-0 flex justify-center"><Toasts /></div>
            <div className="absolute bottom-0 left-0"><PerfPanel /></div>
          </div>
          <aside
            aria-label="Inspector"
            className={`panel pointer-events-auto absolute inset-y-0 right-0 z-10 w-[340px] max-w-[calc(100vw-24px)] overflow-hidden rounded-[10px] transition-[opacity,transform] duration-500 ease-out-quint lg:static
              ${introDone && rightOpen ? 'translate-x-0 opacity-100' : 'pointer-events-none translate-x-[110%] opacity-0'}`}
            style={enter(200)}
          >
            <RightPanel />
          </aside>
          {introDone && (
            <div className="pointer-events-auto absolute right-0 top-0 flex flex-col gap-1.5 lg:hidden">
              {!rightOpen && (
                <button className="panel rounded-md px-2.5 py-1.5 text-[12px] text-fog" onClick={() => set({ rightOpen: true })}>Inspector</button>
              )}
            </div>
          )}
        </div>
        <div className={`transition-[opacity,transform] duration-700 ease-out-quint ${introDone ? 'opacity-100' : 'translate-y-3 opacity-0'}`} style={enter(260)}>
          <BottomBar />
        </div>
      </div>
      <HelpDialog />
    </div>
  )
}
