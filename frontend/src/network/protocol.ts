/**
 * Wire contracts mirroring com.synapsecity.websocket.protocol.Messages (JSON) and
 * BinaryFrameEncoder (binary). The Java simulation is authoritative; nothing here simulates.
 */

export const VEHICLE_TYPES = ['CAR', 'TAXI', 'BUS', 'DELIVERY', 'EMERGENCY'] as const
export type VehicleType = (typeof VEHICLE_TYPES)[number]

export const VEHICLE_STATES = [
  'SPAWNING',
  'CRUISING',
  'APPROACHING_INTERSECTION',
  'WAITING_AT_SIGNAL',
  'CROSSING_INTERSECTION',
  'REPLANNING',
  'ARRIVING',
  'DWELLING',
  'DESPAWNING',
] as const
export type VehicleState = (typeof VEHICLE_STATES)[number]

export type Layout = 'GRID' | 'RADIAL' | 'ORGANIC'
export type SignalMode = 'FIXED' | 'ADAPTIVE'
export type RoutingAlgorithm = 'DIJKSTRA' | 'A_STAR'
export type DemandProfile = 'BALANCED' | 'COMMUTE' | 'CENTRAL'
export type ScenarioId = 'RUSH_HOUR' | 'GRIDLOCK' | 'EMERGENCY' | 'ROAD_FAILURE' | 'SMART_SIGNALS'

export interface NodeDto { id: number; x: number; y: number; r: number; signalized: boolean; degree: number }
export interface RoadDto {
  id: number; a: number; b: number; cls: 'ARTERIAL' | 'COLLECTOR' | 'LOCAL'; lanes: number
  halfWidth: number; median: number; forward: number; backward: number; closed: boolean
}
export interface SegmentDto {
  id: number; road: number; from: number; to: number; lanes: number
  sx: number; sy: number; ex: number; ey: number; length: number; speedLimit: number; capacity: number
}
export interface ApproachDto { index: number; node: number; segment: number; phase: number; x: number; y: number; angle: number }
export interface ParkDto { id: number; name: string; x: number; y: number; r: number }
export interface BusStopDto { segment: number; x: number; y: number; angle: number }

export interface CityMessage {
  type: 'city'
  name: string; seed: number; layout: Layout; radius: number
  stationNode: number; stationBuilding: number
  nodes: NodeDto[]; roads: RoadDto[]; segments: SegmentDto[]; approaches: ApproachDto[]
  /** stride 9: x, y, width, depth, height, rotation, style, district, variant */
  buildings: number[]
  buildingStyles: string[]
  parks: ParkDto[]
  /** stride 2 */
  trees: number[]
  /** stride 3 */
  lights: number[]
  busStops: BusStopDto[]
}

export interface LiveMetrics {
  active: number; moving: number; stopped: number; avgSpeed: number; congestionIndex: number; condition: string
  avgCurrentWait: number; queuedAtSignals: number; completedTrips: number; abandonedTrips: number; spawned: number
  avgTripTime: number; avgTripWait: number; p90TripTime: number; travelTimeIndex: number; throughputPerMin: number
  emergencyAvgResponse: number; emergencyResponses: number; reroutes: number; utilization: number
  closedRoads: number; activeIncidents: number
}

export interface PerformanceStats {
  steps: number; avgStepMicros: number; p95StepMicros: number; maxStepMicros: number
  routingCalls: number; avgRoutingMicros: number; avgExploredNodes: number; routingFailures: number; safetyClamps: number
}

export interface ServerStats {
  clients: number; lastFrameBytes: number; framesPerSecond: number; stepsPerSecond: number; simSecondsPerWallSecond: number
}

export interface EventDto {
  id: number; tick: number; time: number; type: string; severity: 'INFO' | 'NOTICE' | 'WARNING' | 'CRITICAL'
  title: string; detail: string; vehicleId: number; roadId: number; nodeId: number; x: number | null; y: number | null
}

export interface IncidentDto { id: number; roadId: number; segmentId: number; startTime: number; endTime: number; x: number; y: number }

export interface MetricsSample {
  time: number; active: number; avgSpeed: number; congestionIndex: number; queued: number; throughputPerMin: number
  avgTripTime: number; avgTripWait: number; completed: number; travelTimeIndex: number
}

export interface StateMessage {
  type: 'state'
  tick: number; time: number; running: boolean; speed: number; signalMode: SignalMode; routing: RoutingAlgorithm
  demandProfile: DemandProfile; targetVehicles: number; scenario: ScenarioId | null; seed: number; layout: Layout
  metrics: LiveMetrics; perf: PerformanceStats; server: ServerStats
  incidents: IncidentDto[]; closedRoads: number[]; events: EventDto[]; series: MetricsSample[]; comparisonRunning: boolean
}

export interface DecisionDto { time: number; decision: string; text: string }
export interface VehicleDetail {
  type: 'vehicle'
  id: number; vehicleType: VehicleType; state: VehicleState; stateFor: number; decision: string; decisionText: string
  history: DecisionDto[]; speed: number; accel: number; speedLimit: number; segment: number; road: number; lane: number
  x: number; y: number; heading: number; routePoints: number[]; routeSegments: number[]; destX: number; destY: number
  analysis: {
    algorithm: RoutingAlgorithm; exploredNodes: number; computeMicros: number; distance: number; plannedCost: number
    remainingDistance: number; estimatedRemaining: number; remainingSegments: number; routeChanges: number; hasRoute: boolean
  }
  trip: { elapsed: number; waitTime: number; distance: number; freeFlowEstimate: number; stoppedFor: number }
  driver: { speedFactor: number; headwayFactor: number; accelFactor: number; rerouteAwareness: number }
  siren: boolean; stopIndex: number; stopCount: number
}

export interface IntersectionDetail {
  type: 'intersection'
  node: number; x: number; y: number; signalized: boolean; mode: SignalMode | 'UNSIGNALIZED'
  phase: number; nextPhase: number; phaseCount: number; stage: 'GREEN' | 'YELLOW' | 'ALL_RED'
  stageElapsed: number; stageRemaining: number; preempted: boolean; preemptVehicle: number
  entries: number; phaseChanges: number; preemptions: number; occupants: number
  approaches: { segment: number; road: number; phase: number; color: 'RED' | 'YELLOW' | 'GREEN'; queue: number; demand: number; totalWait: number; maxWait: number; heading: number }[]
  phases: { index: number; segments: number[]; pressure: number; demand: number; sinceServed: number }[]
}

export interface RunSummary {
  mode: SignalMode; completedTrips: number; avgTripTime: number; avgTripWait: number; avgSpeed: number
  meanCongestion: number; p90TripTime: number; throughputPerMin: number; peakQueue: number
  emergencyAvgResponse: number; abandoned: number; stateHash: number; wallMillis: number
  /** rows: time, congestion, avgSpeed, active, throughputPerMin, queued */
  series: number[][]
}
export interface ComparisonResult {
  seed: number; layout: Layout; targetVehicles: number; scenario: string; durationSeconds: number
  fixed: RunSummary; adaptive: RunSummary
}
export interface ComparisonMessage {
  type: 'comparison'; status: 'running' | 'done' | 'error'; progress: number; message: string | null; result: ComparisonResult | null
}

export interface HelloMessage { type: 'hello'; protocolVersion: number; dt: number; binaryVehicleStride: number }
export interface ErrorMessage { type: 'error'; message: string }
export interface AckMessage { type: 'vehicleGone' | 'ack'; command: string; message: string }

export type ServerMessage =
  | HelloMessage | CityMessage | StateMessage | VehicleDetail | IntersectionDetail | ComparisonMessage | ErrorMessage | AckMessage

export type ClientCommand =
  | { type: 'PAUSE' } | { type: 'RESUME' } | { type: 'STEP'; ticks?: number }
  | { type: 'SET_SPEED'; value: number }
  | { type: 'ADD_VEHICLES'; count: number; vehicleType: VehicleType }
  | { type: 'SET_DENSITY'; value: number }
  | { type: 'SET_DEMAND'; profile: DemandProfile }
  | { type: 'CLOSE_ROAD'; roadId: number } | { type: 'OPEN_ROAD'; roadId: number }
  | { type: 'CREATE_INCIDENT'; roadId?: number | null }
  | { type: 'DISPATCH_EMERGENCY'; roadId?: number | null }
  | { type: 'SET_SIGNAL_MODE'; mode: SignalMode }
  | { type: 'SET_ROUTING'; algorithm: RoutingAlgorithm }
  | { type: 'SELECT_VEHICLE'; vehicleId: number | null }
  | { type: 'INSPECT_INTERSECTION'; nodeId: number | null }
  | { type: 'RESET' }
  | { type: 'REGENERATE'; seed: number; layout: Layout }
  | { type: 'LOAD_SCENARIO'; scenario: ScenarioId }
  | { type: 'RUN_COMPARISON'; durationSeconds?: number }

/** Decoded binary snapshot. Typed arrays are indexed by vehicle slot within this frame. */
export interface Frame {
  tick: number
  time: number
  count: number
  ids: Uint32Array
  x: Float32Array
  y: Float32Array
  heading: Float32Array
  speed: Float32Array
  type: Uint8Array
  state: Uint8Array
  flags: Uint8Array
  fade: Float32Array
  congestion: Uint8Array
  colors: Uint8Array
  receivedAt: number
}

export const FLAG_BRAKING = 1
export const FLAG_SIREN = 2
export const FLAG_IN_JUNCTION = 4

const HEADER = 16
const STRIDE = 20

export function decodeFrame(buf: ArrayBuffer, receivedAt: number): Frame | null {
  const dv = new DataView(buf)
  if (buf.byteLength < HEADER || dv.getUint8(0) !== 1) return null
  const count = dv.getUint16(2, true)
  const tick = dv.getUint32(4, true)
  const time = dv.getFloat32(8, true)
  const segCount = dv.getUint16(12, true)
  const apprCount = dv.getUint16(14, true)
  const f: Frame = {
    tick, time, count, receivedAt,
    ids: new Uint32Array(count), x: new Float32Array(count), y: new Float32Array(count),
    heading: new Float32Array(count), speed: new Float32Array(count), type: new Uint8Array(count),
    state: new Uint8Array(count), flags: new Uint8Array(count), fade: new Float32Array(count),
    congestion: new Uint8Array(buf, HEADER + count * STRIDE, segCount).slice(),
    colors: new Uint8Array(buf, HEADER + count * STRIDE + segCount, apprCount).slice(),
  }
  let o = HEADER
  for (let i = 0; i < count; i++, o += STRIDE) {
    f.ids[i] = dv.getUint32(o, true)
    f.x[i] = dv.getFloat32(o + 4, true)
    f.y[i] = dv.getFloat32(o + 8, true)
    f.heading[i] = dv.getInt16(o + 12, true) / 10000
    f.speed[i] = dv.getUint8(o + 14) / 4
    f.type[i] = dv.getUint8(o + 15)
    f.state[i] = dv.getUint8(o + 16)
    f.flags[i] = dv.getUint8(o + 17)
    f.fade[i] = dv.getUint8(o + 18) / 255
  }
  return f
}
