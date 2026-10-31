import { useStore } from '../../state/store'
import type { IntersectionDetail, VehicleDetail } from '../../network/protocol'
import { Button, Row, Stat, fmt } from '../controls/primitives'
import { AreaChart, CompareBar, DualChart, Meter } from '../analytics/Charts'

const TYPE_LABEL: Record<string, string> = { CAR: 'Car', TAXI: 'Taxi', BUS: 'Bus', DELIVERY: 'Delivery van', EMERGENCY: 'Emergency unit' }
const STATE_LABEL: Record<string, string> = {
  SPAWNING: 'Spawning', CRUISING: 'Cruising', APPROACHING_INTERSECTION: 'Approaching junction',
  WAITING_AT_SIGNAL: 'Waiting at junction', CROSSING_INTERSECTION: 'Crossing junction', REPLANNING: 'Replanning',
  ARRIVING: 'Arriving', DWELLING: 'Dwelling', DESPAWNING: 'Leaving network',
}
export const STATE_ORDER = ['SPAWNING', 'CRUISING', 'APPROACHING_INTERSECTION', 'WAITING_AT_SIGNAL', 'CROSSING_INTERSECTION', 'ARRIVING', 'DESPAWNING']

function Header({ title, sub, onClose, tone }: { title: string; sub?: string; onClose?: () => void; tone?: 'sodium' }) {
  return (
    <div className="flex items-start justify-between border-b border-hair px-4 py-3">
      <div className="min-w-0">
        <h2 className={`text-[15px] font-semibold ${tone === 'sodium' ? 'text-sodium' : 'text-paper'}`}>{title}</h2>
        {sub && <p className="truncate text-[12px] text-fog">{sub}</p>}
      </div>
      {onClose && (
        <button onClick={onClose} className="-mr-1 rounded p-1 text-dim hover:text-paper" aria-label="Close inspector">
          <svg width="14" height="14" viewBox="0 0 14 14"><path d="M3 3l8 8M11 3l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg>
        </button>
      )}
    </div>
  )
}

function Block({ title, children, aside }: { title: string; children: React.ReactNode; aside?: React.ReactNode }) {
  return (
    <div className="border-b border-hair px-4 py-3 last:border-b-0">
      <div className="mb-2 flex items-baseline justify-between">
        <h3 className="text-[12.5px] font-semibold text-paper">{title}</h3>
        {aside && <span className="text-[11px] text-dim">{aside}</span>}
      </div>
      {children}
    </div>
  )
}

export function RightPanel() {
  const vehicle = useStore((s) => s.vehicle)
  const intersection = useStore((s) => s.intersection)
  const selectedVehicle = useStore((s) => s.selectedVehicle)
  const selectedNode = useStore((s) => s.selectedNode)
  const selectedRoad = useStore((s) => s.selectedRoad)
  return (
    <div className="scroll-quiet h-full overflow-y-auto">
      {selectedVehicle !== null ? (vehicle ? <VehicleInspector v={vehicle} /> : <Loading label={`Vehicle ${selectedVehicle}`} />)
        : selectedNode !== null ? (intersection ? <IntersectionInspector ix={intersection} /> : <Loading label={`Junction ${selectedNode}`} />)
          : selectedRoad !== null ? <RoadInspector roadId={selectedRoad} />
            : <NetworkOverview />}
    </div>
  )
}

function Loading({ label }: { label: string }) {
  return <div className="px-4 py-6 text-[12px] text-fog">Requesting {label} from the simulation…</div>
}

function VehicleInspector({ v }: { v: VehicleDetail }) {
  const selectVehicle = useStore((s) => s.selectVehicle)
  const follow = useStore((s) => s.follow)
  const set = useStore((s) => s.set)
  const focus = useStore((s) => s.focus)
  const a = v.analysis
  const tti = v.trip.freeFlowEstimate > 1 ? v.trip.elapsed / v.trip.freeFlowEstimate : 1
  return (
    <div>
      <Header title={`${TYPE_LABEL[v.vehicleType]} ${v.id}`} sub={`${STATE_LABEL[v.state]} for ${fmt.s(v.stateFor)}`}
        tone={v.siren ? 'sodium' : undefined} onClose={() => selectVehicle(null)} />
      <div className="border-b border-hair px-4 py-3">
        <div className="text-[11px] text-dim">Current decision</div>
        <p className="mt-0.5 text-[13.5px] leading-snug text-paper">{v.decisionText}</p>
        <div className="mt-3 grid grid-cols-3 gap-3">
          <Stat label="Speed" value={fmt.kmh(v.speed)} unit="km/h" tone="cyan" />
          <Stat label="Limit" value={fmt.kmh(v.speedLimit)} unit="km/h" />
          <Stat label="Accel" value={v.accel.toFixed(1)} unit="m/s²" tone={v.accel < -1.5 ? 'sodium' : undefined} />
        </div>
        <div className="mt-3 flex gap-1.5">
          <Button tone={follow ? 'primary' : 'default'} kbd="F" onClick={() => set({ follow: !follow })}>{follow ? 'Following' : 'Follow'}</Button>
          <Button tone="ghost" onClick={() => focus(v.x, v.y, 1.6)}>Focus</Button>
        </div>
      </div>
      <Block title="Route analysis" aside={a.algorithm === 'A_STAR' ? 'A* search' : 'Dijkstra'}>
        {a.hasRoute ? (
          <>
            <div className="grid grid-cols-2 gap-x-4 gap-y-3">
              <Stat label="Estimated remaining" value={a.estimatedRemaining >= 0 ? fmt.s(a.estimatedRemaining) : 'blocked'} tone="cyan" />
              <Stat label="Remaining distance" value={fmt.m(a.remainingDistance)} />
              <Stat label="Nodes explored" value={a.exploredNodes} />
              <Stat label="Search time" value={a.computeMicros.toFixed(1)} unit="µs" />
            </div>
            <div className="mt-3">
              <Row k="Planned path length" v={fmt.m(a.distance)} />
              <Row k="Planned cost at departure" v={fmt.s(a.plannedCost)} />
              <Row k="Segments ahead" v={a.remainingSegments} />
              <Row k="Route changes" v={a.routeChanges} accent={a.routeChanges > 0} />
            </div>
          </>
        ) : <p className="text-[12px] text-fog">Destination is on the current road; no search needed.</p>}
      </Block>
      <Block title="Trip" aside={v.stopCount ? `stop ${Math.min(v.stopIndex + 1, v.stopCount)} of ${v.stopCount}` : undefined}>
        <Row k="Elapsed" v={fmt.s(v.trip.elapsed)} />
        <Row k="Time stopped" v={fmt.s(v.trip.waitTime)} />
        <Row k="Distance driven" v={fmt.m(v.trip.distance)} />
        <Row k="Free-flow estimate" v={fmt.s(v.trip.freeFlowEstimate)} />
        <Row k="Delay ratio so far" v={`${tti.toFixed(2)}×`} accent={tti < 1.3} />
      </Block>
      <Block title="Decision log">
        <ol className="space-y-1.5">
          {v.history.map((h, i) => (
            <li key={`${h.time}-${i}`} className="flex gap-3 text-[12px]">
              <span className="w-12 shrink-0 text-dim">{fmt.clock(h.time).slice(3)}</span>
              <span className={i === 0 ? 'text-paper' : 'text-fog'}>{h.text}</span>
            </li>
          ))}
        </ol>
      </Block>
      <Block title="Driver profile">
        <Row k="Desired speed factor" v={`${v.driver.speedFactor.toFixed(2)}×`} />
        <Row k="Time headway factor" v={`${v.driver.headwayFactor.toFixed(2)}×`} />
        <Row k="Acceleration factor" v={`${v.driver.accelFactor.toFixed(2)}×`} />
        <Row k="Reroute awareness" v={`${Math.round(v.driver.rerouteAwareness * 100)}%`} />
      </Block>
    </div>
  )
}

const COLOR_CLASS = { RED: 'bg-danger', YELLOW: 'bg-sodium', GREEN: 'bg-go' }

function IntersectionInspector({ ix }: { ix: IntersectionDetail }) {
  const selectNode = useStore((s) => s.selectNode)
  const maxPressure = Math.max(1, ...ix.phases.map((p) => p.pressure))
  const stageLabel = ix.stage === 'GREEN' ? 'Green' : ix.stage === 'YELLOW' ? 'Amber clearance' : 'All-red clearance'
  return (
    <div>
      <Header title={`Junction ${ix.node}`}
        sub={ix.signalized ? `${ix.mode === 'ADAPTIVE' ? 'Adaptive' : 'Fixed-time'} signal, ${ix.phaseCount} phases` : 'Unsignalised: first-arrival priority'}
        onClose={() => selectNode(null)} />
      {ix.signalized && (
        <div className="border-b border-hair px-4 py-3">
          <div className="grid grid-cols-3 gap-3">
            <Stat label="Phase" value={`${ix.phase + 1}/${ix.phaseCount}`} tone="cyan" />
            <Stat label={stageLabel} value={ix.stageElapsed.toFixed(0)} unit="s" />
            <Stat label="Remaining" value={ix.stageRemaining >= 0 ? ix.stageRemaining.toFixed(0) : 'demand'} unit={ix.stageRemaining >= 0 ? 's' : undefined} />
          </div>
          {ix.preempted && <p className="mt-2 text-[12px] text-sodium">Emergency priority active for unit {ix.preemptVehicle}</p>}
        </div>
      )}
      <Block title="Approaches" aside="queue · detected · longest wait">
        <div className="space-y-2">
          {ix.approaches.map((a) => (
            <div key={a.segment} className="flex items-center gap-2.5 text-[12px]">
              <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${COLOR_CLASS[a.color]}`} />
              <svg width="14" height="14" viewBox="-7 -7 14 14" className="shrink-0 text-fog" style={{ transform: `rotate(${-a.heading}rad)` }}>
                <path d="M-5 0H4M1-3 4 0 1 3" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinecap="round" />
              </svg>
              <span className="w-16 text-fog">Road {a.road}</span>
              <div className="flex-1"><Meter value={a.queue / 12} color={a.queue > 6 ? 'var(--color-sodium)' : 'var(--color-cyan)'} /></div>
              <span className="w-[72px] text-right text-paper">{a.queue} · {a.demand} · {a.maxWait.toFixed(0)}s</span>
            </div>
          ))}
        </div>
      </Block>
      {ix.signalized && (
        <Block title="Phase pressure" aside="adaptive score">
          <div className="space-y-2">
            {ix.phases.map((p) => (
              <div key={p.index} className="text-[12px]">
                <div className="flex justify-between">
                  <span className={p.index === ix.phase ? 'text-cyan' : 'text-fog'}>Phase {p.index + 1}{p.index === ix.phase ? ' (serving)' : ''}</span>
                  <span className="text-paper">{p.pressure.toFixed(1)} <span className="text-dim">· waited {p.sinceServed.toFixed(0)} s</span></span>
                </div>
                <div className="mt-1"><Meter value={p.pressure / maxPressure} color={p.sinceServed > 60 ? 'var(--color-sodium)' : 'var(--color-cyan)'} /></div>
              </div>
            ))}
          </div>
          <p className="mt-2.5 text-[11.5px] leading-snug text-dim">
            Pressure = queue + 0.35 × detected + longest wait ⁄ 8. Phases waiting over 75 s are served first.
          </p>
        </Block>
      )}
      <Block title="Counters">
        <Row k="Vehicles admitted to box" v={ix.entries} />
        <Row k="Phase changes" v={ix.phaseChanges} />
        <Row k="Emergency preemptions" v={ix.preemptions} />
        <Row k="Box reservations now" v={ix.occupants} />
      </Block>
    </div>
  )
}

function RoadInspector({ roadId }: { roadId: number }) {
  const city = useStore((s) => s.city)
  const sim = useStore((s) => s.sim)
  const send = useStore((s) => s.send)
  const selectRoad = useStore((s) => s.selectRoad)
  if (!city || !sim) return null
  const r = city.roads[roadId]
  const closed = sim.closedRoads.includes(roadId)
  const segs = [city.segments[r.forward], r.backward >= 0 ? city.segments[r.backward] : null].filter(Boolean)
  const incident = sim.incidents.find((i) => i.roadId === roadId)
  const cls = r.cls === 'ARTERIAL' ? 'Arterial' : r.cls === 'COLLECTOR' ? 'Collector' : 'Local street'
  return (
    <div>
      <Header title={`Road ${roadId}`} sub={`${cls}, ${r.lanes} lane${r.lanes > 1 ? 's' : ''} each way`} onClose={() => selectRoad(null)} />
      <div className="border-b border-hair px-4 py-3">
        <div className="grid grid-cols-2 gap-3">
          <Stat label="Speed limit" value={fmt.kmh(segs[0]!.speedLimit)} unit="km/h" />
          <Stat label="Length" value={segs[0]!.length.toFixed(0)} unit="m" />
        </div>
        {closed && <p className="mt-2 text-[12px] text-danger">Closed. Routers treat it as impassable.</p>}
        {incident && <p className="mt-2 text-[12px] text-sodium">Incident until t = {fmt.clock(incident.endTime)}</p>}
        <div className="mt-3 grid grid-cols-2 gap-1.5">
          <Button tone={closed ? 'primary' : 'warn'} onClick={() => send({ type: closed ? 'OPEN_ROAD' : 'CLOSE_ROAD', roadId })}>
            {closed ? 'Reopen road' : 'Close road'}
          </Button>
          <Button tone="warn" disabled={closed || !!incident} onClick={() => send({ type: 'CREATE_INCIDENT', roadId })}>Create incident</Button>
          <Button className="col-span-2" disabled={closed} onClick={() => send({ type: 'DISPATCH_EMERGENCY', roadId })}>Dispatch emergency unit here</Button>
        </div>
      </div>
      <Block title="Directions">
        {segs.map((s) => (
          <div key={s!.id} className="mb-2 last:mb-0">
            <Row k={`Node ${s!.from} to ${s!.to}`} v={`capacity ${s!.capacity.toFixed(0)} veh`} />
          </div>
        ))}
        <p className="text-[11.5px] text-dim">Live congestion on each direction is drawn on the road; switch on the heatmap to see the full ramp.</p>
      </Block>
    </div>
  )
}

function NetworkOverview() {
  const sim = useStore((s) => s.sim)
  const series = useStore((s) => s.series)
  if (!sim) return null
  const m = sim.metrics
  const efficiency = 100 - m.congestionIndex
  return (
    <div>
      <Header title="Network" sub="Measured from the running simulation" />
      <div className="grid grid-cols-2 gap-x-4 gap-y-3 border-b border-hair px-4 py-3">
        <Stat label="Congestion index" value={m.congestionIndex.toFixed(0)} tone={m.congestionIndex > 70 ? 'danger' : m.congestionIndex > 55 ? 'sodium' : 'cyan'} />
        <Stat label="Average speed" value={fmt.kmh(m.avgSpeed)} unit="km/h" />
        <Stat label="Trips completed" value={m.completedTrips} />
        <Stat label="Throughput" value={m.throughputPerMin.toFixed(0)} unit="/min" />
        <Stat label="Average trip" value={fmt.s(m.avgTripTime)} />
        <Stat label="Average stopped" value={fmt.s(m.avgTripWait)} />
      </div>
      <Block title="Congestion index" aside="last 60 min, 5 s samples">
        <AreaChart values={series.map((s) => s.congestionIndex)} min={0} max={100} color="var(--color-sodium)" label="Congestion index over time" />
      </Block>
      <Block title="Average network speed" aside="km/h">
        <AreaChart values={series.map((s) => s.avgSpeed * 3.6)} min={0} color="var(--color-cyan)" label="Average speed over time" />
      </Block>
      <Block title="Throughput" aside="trips per minute">
        <AreaChart values={series.map((s) => s.throughputPerMin)} min={0} color="var(--color-paper)" label="Throughput over time" />
      </Block>
      <Block title="Network health">
        <Row k="Flow efficiency (speed ⁄ limit)" v={`${efficiency.toFixed(0)}%`} />
        <Row k="Travel time index (recent trips)" v={`${m.travelTimeIndex.toFixed(2)}×`} />
        <Row k="90th percentile trip" v={fmt.s(m.p90TripTime)} />
        <Row k="Vehicles queued at signals" v={m.queuedAtSignals} />
        <Row k="Road utilisation (occupied)" v={`${(m.utilization * 100).toFixed(0)}%`} />
        <Row k="Reroutes" v={m.reroutes} />
        <Row k="Emergency responses" v={m.emergencyResponses ? `${m.emergencyResponses}, avg ${fmt.s(m.emergencyAvgResponse)}` : 'none yet'} />
        <Row k="Removed by gridlock relief" v={m.abandonedTrips} accent={false} />
      </Block>
      <ComparisonResults />
      <p className="px-4 py-3 text-[11px] leading-snug text-dim">
        All figures are simulated by the Java engine. They are not real-world traffic data.
      </p>
    </div>
  )
}

function ComparisonResults() {
  const c = useStore((s) => s.comparison)
  if (!c.result) {
    if (c.status === 'error') return <Block title="Strategy comparison"><p className="text-[12px] text-danger">{c.message}</p></Block>
    return null
  }
  const { fixed, adaptive } = c.result
  return (
    <Block title="Strategy comparison" aside={`seed ${c.result.seed}, ${c.result.durationSeconds / 60} min`}>
      <div className="space-y-3">
        <CompareBar label="Average trip time" a={fixed.avgTripTime} b={adaptive.avgTripTime} unit="s" format={(v) => v.toFixed(0)} />
        <CompareBar label="Average time stopped" a={fixed.avgTripWait} b={adaptive.avgTripWait} unit="s" format={(v) => v.toFixed(0)} />
        <CompareBar label="Trips completed" a={fixed.completedTrips} b={adaptive.completedTrips} lowerIsBetter={false} format={(v) => v.toFixed(0)} />
        <CompareBar label="Mean congestion index" a={fixed.meanCongestion} b={adaptive.meanCongestion} format={(v) => v.toFixed(0)} />
        {(fixed.emergencyAvgResponse > 0 || adaptive.emergencyAvgResponse > 0) && (
          <CompareBar label="Emergency response" a={fixed.emergencyAvgResponse} b={adaptive.emergencyAvgResponse} unit="s" format={(v) => v.toFixed(0)} />
        )}
        <div>
          <div className="mb-1 flex justify-between text-[11.5px] text-fog">
            <span>Congestion over time</span>
            <span><span className="text-fog">fixed</span> · <span className="text-cyan">adaptive</span></span>
          </div>
          <DualChart a={fixed.series.map((r) => r[1])} b={adaptive.series.map((r) => r[1])} colorA="var(--color-fog)" colorB="var(--color-cyan)" label="Congestion index, fixed vs adaptive" />
        </div>
        <p className="text-[11px] leading-snug text-dim">
          Same seed, demand and script in two independent deterministic engines ({fixed.wallMillis.toFixed(0)} ms and {adaptive.wallMillis.toFixed(0)} ms of compute).
        </p>
      </div>
    </Block>
  )
}
