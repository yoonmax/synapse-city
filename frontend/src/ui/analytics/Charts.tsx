import { useId, useMemo } from 'react'

interface SeriesProps {
  values: number[]
  height?: number
  color?: string
  min?: number
  max?: number
  fill?: boolean
  label: string
}

function pathFor(values: number[], w: number, h: number, min: number, max: number) {
  if (values.length < 2) return ''
  const span = max - min || 1
  return values.map((v, i) => {
    const x = (i / (values.length - 1)) * w
    const y = h - ((Math.min(max, Math.max(min, v)) - min) / span) * (h - 4) - 2
    return `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

/** Area sparkline of a real metric series. Renders an explicit empty state rather than inventing data. */
export function AreaChart({ values, height = 56, color = 'var(--color-cyan)', min, max, fill = true, label }: SeriesProps) {
  const id = useId()
  const w = 280
  const lo = min ?? Math.min(...values, 0)
  const hi = max ?? Math.max(...values, 1)
  const d = useMemo(() => pathFor(values, w, height, lo, hi), [values, height, lo, hi])
  if (values.length < 2) {
    return <div className="flex items-center text-[11px] text-dim" style={{ height }}>Collecting samples (every 5 s of simulated time)</div>
  }
  return (
    <svg viewBox={`0 0 ${w} ${height}`} preserveAspectRatio="none" className="block w-full" style={{ height }} role="img" aria-label={label}>
      <defs>
        <linearGradient id={id} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor={color} stopOpacity="0.32" />
          <stop offset="1" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      {[0.25, 0.5, 0.75].map((g) => <line key={g} x1="0" x2={w} y1={height * g} y2={height * g} stroke="var(--color-hair)" strokeWidth="1" vectorEffect="non-scaling-stroke" />)}
      {fill && <path d={`${d} L${w},${height} L0,${height} Z`} fill={`url(#${id})`} />}
      <path d={d} fill="none" stroke={color} strokeWidth="1.6" vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
    </svg>
  )
}

/** Two series on a shared scale, used for fixed-vs-adaptive comparison. */
export function DualChart({ a, b, colorA, colorB, height = 84, label }: {
  a: number[]; b: number[]; colorA: string; colorB: string; height?: number; label: string
}) {
  const w = 280
  const lo = Math.min(...a, ...b)
  const hi = Math.max(...a, ...b)
  return (
    <svg viewBox={`0 0 ${w} ${height}`} preserveAspectRatio="none" className="block w-full" style={{ height }} role="img" aria-label={label}>
      {[0.25, 0.5, 0.75].map((g) => <line key={g} x1="0" x2={w} y1={height * g} y2={height * g} stroke="var(--color-hair)" vectorEffect="non-scaling-stroke" />)}
      <path d={pathFor(a, w, height, lo, hi)} fill="none" stroke={colorA} strokeWidth="1.6" vectorEffect="non-scaling-stroke" />
      <path d={pathFor(b, w, height, lo, hi)} fill="none" stroke={colorB} strokeWidth="1.6" vectorEffect="non-scaling-stroke" />
    </svg>
  )
}

/** Horizontal paired bars: lower-is-better metrics show which strategy won. */
export function CompareBar({ label, a, b, unit, lowerIsBetter = true, format = (v: number) => v.toFixed(1) }: {
  label: string; a: number; b: number; unit?: string; lowerIsBetter?: boolean; format?: (v: number) => string
}) {
  const max = Math.max(a, b, 1e-9)
  const better = lowerIsBetter ? (b < a ? 'b' : a < b ? 'a' : null) : (b > a ? 'b' : a > b ? 'a' : null)
  const delta = a === 0 ? 0 : ((b - a) / a) * 100
  return (
    <div className="space-y-1">
      <div className="flex items-baseline justify-between text-[11.5px]">
        <span className="text-fog">{label}</span>
        <span className={better === 'b' ? 'text-cyan' : better === 'a' ? 'text-sodium' : 'text-dim'}>
          {delta >= 0 ? '+' : ''}{delta.toFixed(1)}%
        </span>
      </div>
      {([['Fixed', a, 'var(--color-fog)'], ['Adaptive', b, 'var(--color-cyan)']] as const).map(([name, v, c]) => (
        <div key={name} className="flex items-center gap-2 text-[11px]">
          <span className="w-14 text-dim">{name}</span>
          <div className="h-[6px] flex-1 overflow-hidden rounded-full bg-deep">
            <div className="h-full rounded-full transition-[width] duration-700 ease-out-quint" style={{ width: `${(v / max) * 100}%`, background: c }} />
          </div>
          <span className="w-16 text-right text-paper">{format(v)}{unit}</span>
        </div>
      ))}
    </div>
  )
}

/** Small horizontal meter (0..1). */
export function Meter({ value, color = 'var(--color-cyan)' }: { value: number; color?: string }) {
  return (
    <div className="h-[4px] w-full overflow-hidden rounded-full bg-deep">
      <div className="h-full rounded-full transition-[width] duration-500 ease-out-quint" style={{ width: `${Math.max(0, Math.min(1, value)) * 100}%`, background: color }} />
    </div>
  )
}
