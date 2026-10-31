import { create } from 'zustand'
import type {
  CityMessage, ClientCommand, ComparisonResult, EventDto, IntersectionDetail, MetricsSample, StateMessage, VehicleDetail,
} from '../network/protocol'

export type Connection = 'connecting' | 'open' | 'closed'
export type CameraMode = 'iso' | 'perspective'
export type Quality = 'high' | 'low'

export interface Toast { id: number; tone: 'info' | 'warn' | 'error' | 'critical'; title: string; detail?: string }

interface Store {
  connection: Connection
  city: CityMessage | null
  cityVersion: number
  sim: StateMessage | null
  events: EventDto[]
  series: MetricsSample[]
  vehicle: VehicleDetail | null
  intersection: IntersectionDetail | null
  selectedVehicle: number | null
  selectedNode: number | null
  selectedRoad: number | null
  comparison: { status: 'idle' | 'running' | 'done' | 'error'; progress: number; message: string | null; result: ComparisonResult | null }
  leftOpen: boolean
  rightOpen: boolean
  overlays: { heatmap: boolean; routes: boolean; signals: boolean }
  cameraMode: CameraMode
  quality: Quality
  introDone: boolean
  follow: boolean
  perfOpen: boolean
  helpOpen: boolean
  toasts: Toast[]
  focusRequest: { x: number; y: number; zoom: number; nonce: number } | null
  send: (cmd: ClientCommand) => void
  setSender: (fn: (cmd: ClientCommand) => void) => void
  set: (partial: Partial<Store>) => void
  toast: (t: Omit<Toast, 'id'>) => void
  dismissToast: (id: number) => void
  selectVehicle: (id: number | null) => void
  selectNode: (id: number | null) => void
  selectRoad: (id: number | null) => void
  focus: (x: number, y: number, zoom?: number) => void
}

let sender: (cmd: ClientCommand) => void = () => {}
let toastId = 1

const prefersLow = typeof window !== 'undefined' &&
  (window.matchMedia('(max-width: 820px)').matches || (navigator.hardwareConcurrency ?? 8) <= 4)

export const useStore = create<Store>((set, get) => ({
  connection: 'connecting',
  city: null,
  cityVersion: 0,
  sim: null,
  events: [],
  series: [],
  vehicle: null,
  intersection: null,
  selectedVehicle: null,
  selectedNode: null,
  selectedRoad: null,
  comparison: { status: 'idle', progress: 0, message: null, result: null },
  leftOpen: true,
  rightOpen: true,
  overlays: { heatmap: false, routes: true, signals: true },
  cameraMode: 'iso',
  quality: prefersLow ? 'low' : 'high',
  introDone: false,
  follow: false,
  perfOpen: false,
  helpOpen: false,
  toasts: [],
  focusRequest: null,
  send: (cmd) => sender(cmd),
  setSender: (fn) => { sender = fn },
  set: (partial) => set(partial),
  toast: (t) => {
    const id = toastId++
    set({ toasts: [...get().toasts.slice(-3), { ...t, id }] })
    setTimeout(() => get().dismissToast(id), t.tone === 'critical' ? 7000 : 4500)
  },
  dismissToast: (id) => set({ toasts: get().toasts.filter((t) => t.id !== id) }),
  selectVehicle: (id) => {
    set({ selectedVehicle: id, selectedNode: null, selectedRoad: null, intersection: null, vehicle: id === null ? null : get().vehicle, rightOpen: id !== null || get().rightOpen })
    sender({ type: 'SELECT_VEHICLE', vehicleId: id })
    sender({ type: 'INSPECT_INTERSECTION', nodeId: null })
  },
  selectNode: (id) => {
    set({ selectedNode: id, selectedVehicle: null, selectedRoad: null, vehicle: null, rightOpen: id !== null || get().rightOpen })
    sender({ type: 'INSPECT_INTERSECTION', nodeId: id })
    sender({ type: 'SELECT_VEHICLE', vehicleId: null })
  },
  selectRoad: (id) => {
    set({ selectedRoad: id, selectedVehicle: null, selectedNode: null, vehicle: null, intersection: null, rightOpen: id !== null || get().rightOpen })
    sender({ type: 'SELECT_VEHICLE', vehicleId: null })
    sender({ type: 'INSPECT_INTERSECTION', nodeId: null })
  },
  focus: (x, y, zoom = 1) => set({ focusRequest: { x, y, zoom, nonce: Math.random() } }),
}))
