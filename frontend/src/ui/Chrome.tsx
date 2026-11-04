import { Fragment, useEffect, useMemo, useState } from 'react'
import { useStore } from '../state/store'
import { RUNS_IN_BROWSER } from '../network/client'
import { renderStats } from '../city/renderer/PerfProbe'
import { Button, Segmented, Toggle, fmt } from './controls/primitives'
import { SPEEDS } from './controls/LeftPanel'
import type { EventDto } from '../network/protocol'

export function Wordmark({ className = '' }: { className?: string }) {
  return (
    <span className={`wordmark inline-flex items-center gap-2 text-paper ${className}`}>
      <svg width="18" height="18" viewBox="0 0 32 32" aria-hidden>
        <path d="M5 23h22M10 9v14M22 9v14M5 9h22" stroke="var(--color-cyan)" strokeWidth="2.6" fill="none" strokeLinecap="round" />
        <circle cx="22" cy="23" r="3.4" fill="var(--color-sodium)" />
      </svg>
      Synapse City
    </span>
  )
}

const CONDITION_TONE: Record<string, string> = {
  'FREE FLOW': 'text-cyan', MODERATE: 'text-paper', HEAVY: 'text-sodium', SEVERE: 'text-danger',
}
const CONDITION_LABEL: Record<string, string> = { 'FREE FLOW': 'Free flow', MODERATE: 'Moderate', HEAVY: 'Heavy', SEVERE: 'Severe' }

export function TopBar() {
  const sim = useStore((s) => s.sim)
  const city = useStore((s) => s.city)
  const connection = useStore((s) => s.connection)
  const set = useStore((s) => s.set)
  const leftOpen = useStore((s) => s.leftOpen)
  const rightOpen = useStore((s) => s.rightOpen)
  const [settings, setSettings] = useState(false)
  const status = connection !== 'open' ? (connection === 'connecting' ? 'Connecting' : 'Offline') : !sim ? 'Starting' : sim.running ? 'Live' : 'Paused'
  const statusTone = connection !== 'open' ? 'bg-danger' : sim?.running ? 'bg-go' : 'bg-sodium'
  return (
    <header className="panel pointer-events-auto relative flex h-12 items-center gap-4 rounded-[10px] px-3 sm:px-4">
      <button className="rounded p-1 text-fog hover:text-paper md:hidden" aria-label="Toggle controls" onClick={() => set({ leftOpen: !leftOpen })}>
        <svg width="16" height="16" viewBox="0 0 16 16"><path d="M2 4h12M2 8h12M2 12h12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg>
      </button>
      <Wordmark className="text-[14px]" />
      <div className="hidden h-5 w-px bg-hair sm:block" />
      <div className="hidden min-w-0 sm:block">
        <div className="truncate text-[13px] text-paper">{city?.name ?? 'Generating city'}</div>
        <div className="truncate text-[11px] text-dim">{city ? `${city.layout.toLowerCase()} layout, seed ${city.seed}` : '…'}</div>
      </div>
      <div className="ml-auto flex items-center gap-4">
        <span className="flex items-center gap-2 text-[12px] text-fog" aria-live="polite">
          <span className={`h-2 w-2 rounded-full ${statusTone} ${status === 'Live' ? 'animate-pulse' : ''}`} />
          {status}
        </span>
        <span className="hidden text-[13px] text-paper sm:inline" title="Simulated time">{fmt.clock(sim?.time ?? 0)}</span>
        {sim && (
          <span className={`hidden text-[12.5px] md:inline ${CONDITION_TONE[sim.metrics.condition] ?? 'text-paper'}`} title="Congestion index: speed deficit vs posted limits">
            {CONDITION_LABEL[sim.metrics.condition] ?? sim.metrics.condition} traffic · {sim.metrics.congestionIndex.toFixed(0)}
          </span>
        )}
        <button className="hidden rounded p-1 text-fog hover:text-paper md:block" onClick={() => set({ leftOpen: !(leftOpen && rightOpen), rightOpen: !(leftOpen && rightOpen) })}
          title="Cinematic view (\\)" aria-label="Toggle panels">
          <svg width="16" height="16" viewBox="0 0 16 16"><rect x="1.5" y="2.5" width="13" height="11" rx="2" stroke="currentColor" fill="none" strokeWidth="1.3" /><path d="M5.5 2.5v11M10.5 2.5v11" stroke="currentColor" strokeWidth="1.3" /></svg>
        </button>
        <button className="rounded p-1 text-fog hover:text-paper" onClick={() => setSettings(!settings)} aria-label="Settings" aria-expanded={settings}>
          <svg width="16" height="16" viewBox="0 0 16 16"><circle cx="8" cy="8" r="2.2" stroke="currentColor" fill="none" strokeWidth="1.3" /><path d="M8 1.5v2M8 12.5v2M1.5 8h2M12.5 8h2M3.4 3.4l1.4 1.4M11.2 11.2l1.4 1.4M3.4 12.6l1.4-1.4M11.2 4.8l1.4-1.4" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" /></svg>
        </button>
      </div>
      {settings && <SettingsPopover onClose={() => setSettings(false)} />}
    </header>
  )
}

/** Downloads the sampled time series the client already holds. */
function exportMetrics() {
  const rows = useStore.getState().series.map((m) => [
    m.time.toFixed(1), m.active, m.avgSpeed.toFixed(3), m.congestionIndex.toFixed(2), m.queued,
    m.throughputPerMin.toFixed(2), m.avgTripTime.toFixed(2), m.avgTripWait.toFixed(2), m.completed, m.travelTimeIndex.toFixed(3),
  ].join(','))
  const csv = [
    '# Synapse City simulated metrics (not real-world traffic data)',
    'time_s,active,avg_speed_mps,congestion_index,queued,throughput_per_min,avg_trip_s,avg_trip_wait_s,completed,travel_time_index',
    ...rows,
  ].join('\n')
  const a = document.createElement('a')
  a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }))
  a.download = 'synapse-city-metrics.csv'
  a.click()
  URL.revokeObjectURL(a.href)
}

function SettingsPopover({ onClose }: { onClose: () => void }) {
  const quality = useStore((s) => s.quality)
  const perfOpen = useStore((s) => s.perfOpen)
  const set = useStore((s) => s.set)
  return (
    <div className="panel absolute right-2 top-14 z-30 w-72 rounded-[10px] p-4" role="dialog" aria-label="Settings">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-[13px] font-semibold">Settings</h2>
        <button className="text-[12px] text-fog hover:text-paper" onClick={onClose}>Done</button>
      </div>
      <div className="mb-1.5 text-[12px] text-fog">Rendering quality</div>
      <Segmented label="Rendering quality" value={quality} onChange={(q) => set({ quality: q })}
        options={[{ value: 'high', label: 'Cinematic' }, { value: 'low', label: 'Performance' }]} />
      <p className="mt-1.5 text-[11.5px] leading-snug text-dim">Performance mode turns off bloom, soft shadows, light pools and trails. The simulation is unaffected.</p>
      <div className="mt-3">
        <Toggle label="Performance overlay" kbd="P" checked={perfOpen} onChange={(v) => set({ perfOpen: v })} />
        <Button tone="ghost" className="mt-2 w-full" onClick={() => { set({ helpOpen: true }); onClose() }}>Keyboard shortcuts</Button>
        <Button tone="ghost" className="mt-1 w-full" onClick={exportMetrics}>Export metrics (CSV)</Button>
        {RUNS_IN_BROWSER && (
          <p className="mt-3 border-t border-hair pt-3 text-[11.5px] leading-snug text-dim">
            This page runs the Java simulation inside your browser, compiled to JavaScript. Your simulation is private and nothing is sent to a server.
          </p>
        )}
      </div>
    </div>
  )
}

export function BottomBar() {
  const sim = useStore((s) => s.sim)
  const send = useStore((s) => s.send)
  if (!sim) return null
  const m = sim.metrics
  return (
    <footer className="panel pointer-events-auto flex flex-wrap items-center gap-x-5 gap-y-2 rounded-[10px] px-3 py-2 sm:flex-nowrap sm:px-4">
      <div className="flex items-center gap-2">
        <button
          onClick={() => send({ type: sim.running ? 'PAUSE' : 'RESUME' })}
          className="flex h-9 w-9 items-center justify-center rounded-full bg-paper text-basalt transition-transform active:scale-95"
          aria-label={sim.running ? 'Pause simulation' : 'Resume simulation'} title="Space"
        >
          {sim.running
            ? <svg width="12" height="12" viewBox="0 0 12 12"><rect x="2" y="1.5" width="2.6" height="9" rx="0.8" fill="currentColor" /><rect x="7.4" y="1.5" width="2.6" height="9" rx="0.8" fill="currentColor" /></svg>
            : <svg width="12" height="12" viewBox="0 0 12 12"><path d="M3 1.6v8.8L10.4 6z" fill="currentColor" /></svg>}
        </button>
        {!sim.running && (
          <button className="rounded px-2 py-1 text-[12px] text-fog hover:text-paper" onClick={() => send({ type: 'STEP', ticks: 10 })} title="Advance one simulated second">
            Step 1 s
          </button>
        )}
        <div className="flex rounded-md bg-deep p-0.5 ring-1 ring-hair" role="radiogroup" aria-label="Simulation speed">
          {SPEEDS.map((v) => (
            <button key={v} role="radio" aria-checked={sim.speed === v} onClick={() => send({ type: 'SET_SPEED', value: v })}
              className={`rounded-[5px] px-1.5 py-1 text-[11.5px] sm:px-2 ${sim.speed === v ? 'bg-slate text-paper' : 'text-dim hover:text-paper'}`}>
              {v < 1 ? `${v}` : v}×
            </button>
          ))}
        </div>
      </div>
      <Timeline time={sim.time} />
      <div className="flex items-center gap-5">
        <Kpi label="Vehicles" value={m.active} />
        <Kpi label="Avg trip" value={fmt.s(m.avgTripTime)} />
        <Kpi label="Flow efficiency" value={`${(100 - m.congestionIndex).toFixed(0)}%`} title="Average speed as a share of the posted limit" />
        <Kpi label="Throughput" value={`${m.throughputPerMin.toFixed(0)}/min`} className="hidden lg:block" />
      </div>
    </footer>
  )
}

function Kpi({ label, value, title, className = '' }: { label: string; value: React.ReactNode; title?: string; className?: string }) {
  return (
    <div className={`min-w-0 ${className}`} title={title}>
      <div className="text-[10.5px] text-dim">{label}</div>
      <div className="text-[14px] font-medium leading-tight text-paper">{value}</div>
    </div>
  )
}

const SEVERITY_COLOR: Record<string, string> = {
  INFO: 'var(--color-dim)', NOTICE: 'var(--color-cyan)', WARNING: 'var(--color-sodium)', CRITICAL: 'var(--color-danger)',
}

/** Event timeline over the last 10 simulated minutes; markers focus the camera on their location. */
function Timeline({ time }: { time: number }) {
  const events = useStore((s) => s.events)
  const focus = useStore((s) => s.focus)
  const [hover, setHover] = useState<EventDto | null>(null)
  const span = 600
  const start = Math.max(0, time - span)
  const visible = useMemo(() => events.filter((e) => e.time >= start && e.type !== 'SCENARIO_STEP'), [events, start])
  const latest = events[events.length - 1]
  return (
    <div className="relative order-last min-w-0 basis-full sm:order-none sm:basis-auto sm:flex-1">
      <div className="mb-1 flex items-baseline justify-between text-[10.5px] text-dim">
        <span className="truncate">{hover ? `${fmt.clock(hover.time)}  ${hover.title}` : latest ? latest.title : 'No events yet'}</span>
        <span className="hidden shrink-0 pl-3 sm:inline">last 10 min</span>
      </div>
      <div className="relative h-5 rounded bg-deep ring-1 ring-hair">
        <div className="absolute inset-y-0 left-0 rounded bg-cyan/[0.06]" style={{ width: `${Math.min(1, time / span) * 100}%` }} />
        {visible.map((e) => {
          const x = ((e.time - start) / span) * 100
          return (
            <button key={e.id}
              className="absolute top-0.5 bottom-0.5 w-[3px] -translate-x-1/2 rounded-full transition-transform hover:scale-y-125"
              style={{ left: `${x}%`, background: SEVERITY_COLOR[e.severity] }}
              onMouseEnter={() => setHover(e)} onMouseLeave={() => setHover(null)} onFocus={() => setHover(e)} onBlur={() => setHover(null)}
              onClick={() => e.x !== null && e.y !== null && focus(e.x, e.y, 1.4)}
              aria-label={`${e.title} at ${fmt.clock(e.time)}`} />
          )
        })}
      </div>
    </div>
  )
}

export function Toasts() {
  const toasts = useStore((s) => s.toasts)
  const dismiss = useStore((s) => s.dismissToast)
  const tone = { info: 'border-l-cyan', warn: 'border-l-sodium', error: 'border-l-danger', critical: 'border-l-danger' }
  return (
    <div className="pointer-events-none flex flex-col items-center gap-2" aria-live="polite">
      {toasts.map((t) => (
        <button key={t.id} onClick={() => dismiss(t.id)}
          className={`panel pointer-events-auto w-[min(380px,90vw)] rounded-lg border-l-2 px-3.5 py-2.5 text-left ${tone[t.tone]} animate-[toast_420ms_cubic-bezier(0.22,1,0.36,1)]`}>
          <div className={`text-[12.5px] font-medium ${t.tone === 'critical' ? 'text-sodium' : 'text-paper'}`}>{t.title}</div>
          {t.detail && <div className="mt-0.5 text-[11.5px] leading-snug text-fog">{t.detail}</div>}
        </button>
      ))}
      <style>{'@keyframes toast { from { opacity: 0; transform: translateY(-8px) } to { opacity: 1; transform: none } }'}</style>
    </div>
  )
}

/** Developer tooling: measured client render cost alongside measured server step cost. */
export function PerfPanel() {
  const open = useStore((s) => s.perfOpen)
  const sim = useStore((s) => s.sim)
  const [, force] = useState(0)
  useEffect(() => {
    if (!open) return
    const id = setInterval(() => force((n) => n + 1), 500)
    return () => clearInterval(id)
  }, [open])
  if (!open || !sim) return null
  const p = sim.perf
  return (
    <div className="panel pointer-events-auto w-60 rounded-[10px] px-3.5 py-3 text-[11.5px]">
      <div className="mb-1.5 flex justify-between"><span className="font-semibold text-paper">Performance</span><span className="text-dim">measured</span></div>
      {([
        ['Render', `${renderStats.fps} fps · ${renderStats.frameMs} ms`],
        ['Draw calls', renderStats.drawCalls],
        ['Triangles', renderStats.triangles.toLocaleString()],
        ['Sim step (avg / p95)', `${p.avgStepMicros.toFixed(0)} / ${p.p95StepMicros.toFixed(0)} µs`],
        ['Sim rate', `${sim.server.stepsPerSecond} steps/s`],
        ['Snapshots', `${sim.server.framesPerSecond}/s · ${(sim.server.lastFrameBytes / 1024).toFixed(1)} kB`],
        ['Routing', `${p.avgRoutingMicros.toFixed(1)} µs · ${p.routingCalls} calls`],
        ['Spacing interventions', p.safetyClamps],
      ] as const).map(([k, v]) => (
        <div key={k} className="flex justify-between py-[2px]"><span className="text-fog">{k}</span><span className="text-paper">{v}</span></div>
      ))}
    </div>
  )
}

export function HelpDialog() {
  const open = useStore((s) => s.helpOpen)
  const set = useStore((s) => s.set)
  if (!open) return null
  const keys: [string, string][] = [
    ['Space', 'Pause or resume'], ['[ and ]', 'Slower or faster'], ['H', 'Congestion heatmap'], ['R', 'Route overlay'],
    ['C', 'Isometric or perspective camera'], ['F', 'Follow selected vehicle'], ['I', 'Create incident'], ['E', 'Dispatch emergency unit'],
    ['\\', 'Hide or show panels'], ['P', 'Performance overlay'], ['Esc', 'Clear selection'],
  ]
  return (
    <div className="pointer-events-auto fixed inset-0 z-40 flex items-center justify-center bg-black/40 p-4" onClick={() => set({ helpOpen: false })}>
      <div className="panel w-[min(420px,100%)] rounded-xl p-5" role="dialog" aria-label="Keyboard shortcuts" onClick={(e) => e.stopPropagation()}>
        <div className="mb-3 flex justify-between"><h2 className="text-[14px] font-semibold">Keyboard shortcuts</h2>
          <button className="text-[12px] text-fog hover:text-paper" onClick={() => set({ helpOpen: false })}>Close</button></div>
        <dl className="grid grid-cols-[auto_1fr] gap-x-5 gap-y-1.5 text-[12.5px]">
          {keys.map(([k, v]) => (<Fragment key={k}><dt><kbd className="rounded bg-white/5 px-1.5 py-0.5 text-paper">{k}</kbd></dt><dd className="text-fog">{v}</dd></Fragment>))}
        </dl>
        <p className="mt-4 text-[12px] leading-relaxed text-dim">Click a vehicle to inspect its decisions and route, a junction to inspect its signal, or a road to close it.</p>
      </div>
    </div>
  )
}

export function useShortcuts() {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement
      if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || e.metaKey || e.ctrlKey || e.altKey) return
      const s = useStore.getState()
      const sim = s.sim
      switch (e.key) {
        case ' ':
          e.preventDefault()
          if (sim) s.send({ type: sim.running ? 'PAUSE' : 'RESUME' })
          break
        case '[': case ']': {
          if (!sim) break
          const i = SPEEDS.indexOf(sim.speed)
          const next = SPEEDS[Math.max(0, Math.min(SPEEDS.length - 1, i + (e.key === ']' ? 1 : -1)))]
          s.send({ type: 'SET_SPEED', value: next })
          break
        }
        case 'h': case 'H': s.set({ overlays: { ...s.overlays, heatmap: !s.overlays.heatmap } }); break
        case 'r': case 'R': s.set({ overlays: { ...s.overlays, routes: !s.overlays.routes } }); break
        case 'c': case 'C': s.set({ cameraMode: s.cameraMode === 'iso' ? 'perspective' : 'iso' }); break
        case 'f': case 'F': if (s.selectedVehicle !== null) s.set({ follow: !s.follow }); break
        case 'i': case 'I': s.send({ type: 'CREATE_INCIDENT', roadId: s.selectedRoad }); break
        case 'e': case 'E': s.send({ type: 'DISPATCH_EMERGENCY', roadId: s.selectedRoad }); break
        case 'p': case 'P': s.set({ perfOpen: !s.perfOpen }); break
        case '\\': { const both = s.leftOpen && s.rightOpen; s.set({ leftOpen: !both, rightOpen: !both }); break }
        case '?': s.set({ helpOpen: !s.helpOpen }); break
        case 'Escape':
          if (s.helpOpen) s.set({ helpOpen: false })
          else { s.selectVehicle(null); s.set({ follow: false }) }
          break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])
}
