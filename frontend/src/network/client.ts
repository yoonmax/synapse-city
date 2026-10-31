import { decodeFrame, type ClientCommand, type ServerMessage } from './protocol'
import { pushFrame, resetFrames } from '../state/frames'
import { useStore } from '../state/store'

/** Single WebSocket to the Java simulation with automatic reconnect and typed dispatch. */
export function connect() {
  let ws: WebSocket | null = null
  let retry = 0
  let closedByUs = false

  const url = (() => {
    const explicit = import.meta.env.VITE_SYNAPSE_WS as string | undefined
    if (explicit) return explicit
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    return `${proto}://${location.host}/ws/simulation`
  })()

  const send = (cmd: ClientCommand) => {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(cmd))
  }
  useStore.getState().setSender(send)

  const open = () => {
    useStore.setState({ connection: 'connecting' })
    ws = new WebSocket(url)
    ws.binaryType = 'arraybuffer'
    ws.onopen = () => {
      retry = 0
      useStore.setState({ connection: 'open' })
    }
    ws.onmessage = (ev) => {
      if (ev.data instanceof ArrayBuffer) {
        const f = decodeFrame(ev.data, performance.now())
        if (f) pushFrame(f)
        return
      }
      handle(JSON.parse(ev.data as string) as ServerMessage)
    }
    ws.onclose = () => {
      useStore.setState({ connection: 'closed' })
      if (closedByUs) return
      retry++
      setTimeout(open, Math.min(8000, 500 * 2 ** Math.min(retry, 4)))
    }
  }
  open()
  return () => {
    closedByUs = true
    ws?.close()
  }
}

function handle(msg: ServerMessage) {
  const s = useStore.getState()
  switch (msg.type) {
    case 'city':
      resetFrames()
      useStore.setState({
        city: msg, cityVersion: s.cityVersion + 1, events: [], series: [], vehicle: null, intersection: null,
        selectedVehicle: null, selectedNode: null, selectedRoad: null,
      })
      break
    case 'state': {
      const events = msg.events.length ? [...s.events, ...msg.events].slice(-300) : s.events
      const series = msg.series.length ? [...s.series, ...msg.series].slice(-720) : s.series
      useStore.setState({ sim: msg, events, series })
      for (const e of msg.events) {
        if (e.type === 'EMERGENCY_DISPATCHED' || e.type === 'ROAD_CLOSED' || e.type === 'INCIDENT_CREATED'
          || e.type === 'GRIDLOCK_RELIEF' || e.type === 'EMERGENCY_ARRIVED' || e.type === 'SCENARIO_LOADED') {
          if (s.introDone && performance.now() - startedAt > 2500) {
            s.toast({ tone: e.severity === 'CRITICAL' ? 'critical' : e.severity === 'WARNING' ? 'warn' : 'info', title: e.title, detail: e.detail })
          }
        }
      }
      break
    }
    case 'vehicle':
      if (msg.id === s.selectedVehicle) useStore.setState({ vehicle: msg })
      break
    case 'intersection':
      if (msg.node === s.selectedNode) useStore.setState({ intersection: msg })
      break
    case 'vehicleGone':
      useStore.setState({ selectedVehicle: null, vehicle: null })
      s.toast({ tone: 'info', title: 'Vehicle left the network', detail: msg.message })
      break
    case 'comparison':
      useStore.setState({
        comparison: {
          status: msg.status, progress: msg.progress, message: msg.message,
          result: msg.result ?? (msg.status === 'running' ? s.comparison.result : null),
        },
      })
      break
    case 'error':
      s.toast({ tone: 'error', title: 'Command rejected', detail: msg.message })
      break
    default:
      break
  }
}

const startedAt = performance.now()
