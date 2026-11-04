import { useEffect, useState } from 'react'
import { useStore } from '../../state/store'
import { RUNS_IN_BROWSER } from '../../network/client'
import type { DemandProfile, Layout } from '../../network/protocol'
import { Button, Section, Segmented, Slider, Toggle } from './primitives'

export const SPEEDS = [0.25, 0.5, 1, 2, 4, 8, 16]

export function LeftPanel() {
  const sim = useStore((s) => s.sim)
  const city = useStore((s) => s.city)
  const send = useStore((s) => s.send)
  const selectedRoad = useStore((s) => s.selectedRoad)
  const overlays = useStore((s) => s.overlays)
  const cameraMode = useStore((s) => s.cameraMode)
  const set = useStore((s) => s.set)
  const [density, setDensity] = useState(300)
  const [seed, setSeed] = useState('')
  const [layout, setLayout] = useState<Layout>('GRID')
  const scenarios = useStore((s) => s.scenarios)

  useEffect(() => { if (sim) setDensity(sim.targetVehicles) }, [sim?.targetVehicles]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (city) { setSeed(String(city.seed)); setLayout(city.layout) }
  }, [city])

  if (!sim) return null
  const closed = selectedRoad !== null && sim.closedRoads.includes(selectedRoad)

  return (
    <div className="flex h-full flex-col">
      <div className="scroll-quiet flex-1 overflow-y-auto">
        <Section title="Traffic demand" hint={`${sim.metrics.active} active`}>
          <Slider label="Target vehicles" value={density} min={0} max={900} step={10}
            onChange={setDensity} onCommit={(v) => send({ type: 'SET_DENSITY', value: v })} />
          <div>
            <div className="mb-1.5 text-[12px] text-fog">Trip pattern</div>
            <Segmented<DemandProfile> label="Trip pattern" value={sim.demandProfile}
              onChange={(p) => send({ type: 'SET_DEMAND', profile: p })}
              options={[
                { value: 'BALANCED', label: 'Balanced', title: 'Origins and destinations spread across the city' },
                { value: 'COMMUTE', label: 'Commute', title: 'Outskirts to downtown' },
                { value: 'CENTRAL', label: 'Central', title: 'Destinations concentrated on the core' },
              ]} />
          </div>
          <div className="grid grid-cols-3 gap-1.5">
            <Button onClick={() => send({ type: 'ADD_VEHICLES', count: 20, vehicleType: 'CAR' })} title="Insert 20 cars into free gaps">+20 cars</Button>
            <Button onClick={() => send({ type: 'ADD_VEHICLES', count: 8, vehicleType: 'TAXI' })} title="Taxis pick up new fares after drop-off">+8 taxis</Button>
            <Button onClick={() => send({ type: 'ADD_VEHICLES', count: 5, vehicleType: 'DELIVERY' })} title="Delivery vans make two stops">+5 vans</Button>
          </div>
        </Section>

        <Section title="Interventions" hint={selectedRoad !== null ? `Road ${selectedRoad}` : 'Click a road'}>
          <p className="text-[12px] leading-relaxed text-fog">
            {selectedRoad !== null
              ? 'Actions apply to the selected road.'
              : 'Select a road in the city to target it, or act on the busiest major road.'}
          </p>
          <div className="grid grid-cols-2 gap-1.5">
            <Button tone="warn" kbd="I" onClick={() => send({ type: 'CREATE_INCIDENT', roadId: selectedRoad })}>Create incident</Button>
            <Button tone="warn" kbd="E" onClick={() => send({ type: 'DISPATCH_EMERGENCY', roadId: selectedRoad })}>Dispatch unit</Button>
            <Button className="col-span-2" disabled={selectedRoad === null}
              onClick={() => selectedRoad !== null && send({ type: closed ? 'OPEN_ROAD' : 'CLOSE_ROAD', roadId: selectedRoad })}>
              {closed ? 'Reopen road' : 'Close road'}{selectedRoad !== null ? ` ${selectedRoad}` : ''}
            </Button>
          </div>
        </Section>

        <Section title="Signal control" hint={sim.signalMode === 'ADAPTIVE' ? 'Adaptive' : 'Fixed time'}>
          <Segmented label="Signal control" value={sim.signalMode}
            onChange={(m) => send({ type: 'SET_SIGNAL_MODE', mode: m })}
            options={[{ value: 'FIXED', label: 'Fixed time' }, { value: 'ADAPTIVE', label: 'Adaptive' }]} />
          <p className="text-[12px] leading-relaxed text-fog">
            {sim.signalMode === 'ADAPTIVE'
              ? 'Each junction scores phases by queue, demand and longest wait, holds 7 to 48 s of green, and serves any approach waiting over 75 s.'
              : '24 s splits for major roads and 15 s for minor ones, offset by position to form an eastbound green wave.'}
          </p>
          <ComparisonLauncher />
        </Section>

        <Section title="Routing" hint={sim.routing === 'A_STAR' ? 'A*' : 'Dijkstra'}>
          <Segmented label="Routing algorithm" value={sim.routing}
            onChange={(a) => send({ type: 'SET_ROUTING', algorithm: a })}
            options={[{ value: 'A_STAR', label: 'A* search' }, { value: 'DIJKSTRA', label: 'Dijkstra' }]} />
          <p className="text-[12px] leading-relaxed text-fog">
            Costs are live travel times: free flow, a BPR volume-delay curve, observed speeds and signal delay. Average search
            explores {sim.perf.avgExploredNodes.toFixed(1)} nodes in {sim.perf.avgRoutingMicros.toFixed(1)} µs.
          </p>
        </Section>

        <Section title="Scenarios" hint={sim.scenario ? scenarios.find((s) => s.id === sim.scenario)?.title : undefined}>
          <div className="space-y-1.5">
            {scenarios.map((s) => (
              <button key={s.id} onClick={() => send({ type: 'LOAD_SCENARIO', scenario: s.id })}
                className={`group block w-full rounded-md px-3 py-2.5 text-left ring-1 transition-colors ${sim.scenario === s.id ? 'bg-cyan/8 ring-cyan/40' : 'bg-deep ring-hair hover:ring-slate'}`}>
                <div className="flex items-baseline justify-between">
                  <span className="text-[13px] font-medium text-paper">{s.title}</span>
                  <span className="text-[11px] text-dim">seed {s.seed}</span>
                </div>
                <div className="mt-0.5 text-[11.5px] leading-snug text-fog">{s.description}</div>
              </button>
            ))}
            {!scenarios.length && <div className="text-[12px] text-dim">Scenario list unavailable.</div>}
          </div>
        </Section>

        <Section title="City" hint={city?.name} defaultOpen={false}>
          <Segmented<Layout> label="Street layout" value={layout} onChange={setLayout}
            options={[{ value: 'GRID', label: 'Grid' }, { value: 'RADIAL', label: 'Radial' }, { value: 'ORGANIC', label: 'Organic' }]} />
          <div className="flex gap-1.5">
            <label className="flex-1">
              <span className="sr-only">Seed</span>
              <input value={seed} onChange={(e) => setSeed(e.target.value.replace(/\D/g, '').slice(0, 9))} inputMode="numeric"
                className="w-full rounded-md bg-deep px-3 py-2 text-[12.5px] text-paper ring-1 ring-hair focus:outline-none focus:ring-cyan/60" placeholder="Seed" />
            </label>
            <Button tone="ghost" title="Random seed" onClick={() => setSeed(String(Math.floor(Math.random() * 1e8)))}>Shuffle</Button>
          </div>
          <Button tone="primary" className="w-full" onClick={() => send({ type: 'REGENERATE', seed: Number(seed || 0), layout })}>
            Generate city
          </Button>
        </Section>

        <Section title="View" defaultOpen={false}>
          <Segmented label="Camera" value={cameraMode} onChange={(m) => set({ cameraMode: m })}
            options={[{ value: 'iso', label: 'Isometric', title: '11° telephoto lens approximating an axonometric view' }, { value: 'perspective', label: 'Perspective' }]} />
          <Toggle label="Congestion heatmap" kbd="H" checked={overlays.heatmap} onChange={(v) => set({ overlays: { ...overlays, heatmap: v } })} />
          <Toggle label="Selected route" kbd="R" checked={overlays.routes} onChange={(v) => set({ overlays: { ...overlays, routes: v } })} />
        </Section>
      </div>
    </div>
  )
}

function ComparisonLauncher() {
  const comparison = useStore((s) => s.comparison)
  const sim = useStore((s) => s.sim)
  const send = useStore((s) => s.send)
  const running = comparison.status === 'running' || sim?.comparisonRunning
  return (
    <div className="rounded-md bg-deep p-3 ring-1 ring-hair">
      <div className="text-[12.5px] font-medium text-paper">Compare strategies</div>
      <p className="mt-1 text-[11.5px] leading-snug text-fog">
        Replays this seed and demand for 10 simulated minutes under each strategy in separate headless engines.
        {RUNS_IN_BROWSER && ' The live city pauses for a few seconds while it runs.'}
      </p>
      {running ? (
        <div className="mt-2.5">
          <div className="h-[3px] overflow-hidden rounded-full bg-slate">
            <div className="h-full bg-cyan transition-[width] duration-300" style={{ width: `${comparison.progress * 100}%` }} />
          </div>
          <div className="mt-1 text-[11px] text-dim">Running {Math.round(comparison.progress * 100)}%</div>
        </div>
      ) : (
        <Button tone="primary" className="mt-2.5 w-full" onClick={() => send({ type: 'RUN_COMPARISON', durationSeconds: 600 })}>
          Run comparison
        </Button>
      )}
    </div>
  )
}
