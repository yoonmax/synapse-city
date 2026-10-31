import { useState, type ReactNode } from 'react'

export function Section({ title, hint, children, defaultOpen = true }: { title: string; hint?: string; children: ReactNode; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <section className="border-b border-hair last:border-b-0">
      <button
        className="flex w-full items-center justify-between px-4 py-3 text-left text-[13px] font-semibold text-paper hover:bg-white/[0.02]"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
      >
        <span>{title}</span>
        <span className="flex items-center gap-2 text-[11px] font-normal text-dim">
          {hint}
          <svg width="10" height="10" viewBox="0 0 10 10" className={`transition-transform duration-300 ease-out-quint ${open ? 'rotate-180' : ''}`}>
            <path d="M2 3.5 5 6.5 8 3.5" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinecap="round" />
          </svg>
        </span>
      </button>
      <div className={`grid transition-[grid-template-rows] duration-300 ease-out-quint ${open ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]'}`}>
        <div className="overflow-hidden">
          <div className="space-y-3 px-4 pb-4">{children}</div>
        </div>
      </div>
    </section>
  )
}

export function Segmented<T extends string | number>({ value, options, onChange, label }: {
  value: T; options: { value: T; label: string; title?: string }[]; onChange: (v: T) => void; label: string
}) {
  return (
    <div role="radiogroup" aria-label={label} className="flex rounded-md bg-deep p-0.5 ring-1 ring-hair">
      {options.map((o) => {
        const active = o.value === value
        return (
          <button
            key={String(o.value)}
            role="radio"
            aria-checked={active}
            title={o.title}
            onClick={() => onChange(o.value)}
            className={`flex-1 rounded-[5px] px-2 py-1.5 text-[12px] transition-colors duration-200 ${active ? 'bg-slate text-paper shadow-[inset_0_-2px_0_var(--color-cyan)]' : 'text-fog hover:text-paper'}`}
          >
            {o.label}
          </button>
        )
      })}
    </div>
  )
}

export function Slider({ label, value, min, max, step = 1, onChange, onCommit, format }: {
  label: string; value: number; min: number; max: number; step?: number
  onChange: (v: number) => void; onCommit?: (v: number) => void; format?: (v: number) => string
}) {
  const fill = ((value - min) / (max - min)) * 100
  return (
    <label className="block">
      <span className="flex items-baseline justify-between text-[12px] text-fog">
        <span>{label}</span>
        <span className="text-paper">{format ? format(value) : value}</span>
      </span>
      <input
        type="range" min={min} max={max} step={step} value={value} className="w-full"
        style={{ ['--fill' as string]: `${fill}%` }}
        onChange={(e) => onChange(Number(e.target.value))}
        onPointerUp={(e) => onCommit?.(Number((e.target as HTMLInputElement).value))}
        onKeyUp={(e) => onCommit?.(Number((e.target as HTMLInputElement).value))}
      />
    </label>
  )
}

type Tone = 'default' | 'primary' | 'warn' | 'ghost'
export function Button({ children, onClick, tone = 'default', disabled, title, className = '', kbd }: {
  children: ReactNode; onClick?: () => void; tone?: Tone; disabled?: boolean; title?: string; className?: string; kbd?: string
}) {
  const tones: Record<Tone, string> = {
    default: 'bg-deep text-paper ring-1 ring-hair hover:ring-slate hover:bg-[#162030]',
    primary: 'bg-cyan/12 text-cyan ring-1 ring-cyan/40 hover:bg-cyan/20',
    warn: 'bg-sodium/10 text-sodium ring-1 ring-sodium/40 hover:bg-sodium/18',
    ghost: 'text-fog hover:text-paper hover:bg-white/[0.04]',
  }
  return (
    <button
      onClick={onClick} disabled={disabled} title={title}
      className={`inline-flex items-center justify-center gap-2 rounded-md px-3 py-2 text-[12.5px] font-medium transition-[background,box-shadow,color,transform] duration-150 active:scale-[0.98] disabled:pointer-events-none disabled:opacity-40 ${tones[tone]} ${className}`}
    >
      {children}
      {kbd && <kbd className="rounded bg-white/5 px-1 text-[10px] font-normal text-dim">{kbd}</kbd>}
    </button>
  )
}

export function Stat({ label, value, unit, tone }: { label: string; value: ReactNode; unit?: string; tone?: 'cyan' | 'sodium' | 'danger' }) {
  const color = tone === 'cyan' ? 'text-cyan' : tone === 'sodium' ? 'text-sodium' : tone === 'danger' ? 'text-danger' : 'text-paper'
  return (
    <div className="min-w-0">
      <div className="truncate text-[11px] text-dim">{label}</div>
      <div className={`text-[17px] font-medium leading-tight ${color}`}>
        {value}{unit && <span className="ml-0.5 text-[11px] font-normal text-dim">{unit}</span>}
      </div>
    </div>
  )
}

export function Row({ k, v, accent }: { k: string; v: ReactNode; accent?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-[3px] text-[12px]">
      <span className="text-fog">{k}</span>
      <span className={`text-right ${accent ? 'text-cyan' : 'text-paper'}`}>{v}</span>
    </div>
  )
}

export function Toggle({ label, checked, onChange, kbd }: { label: string; checked: boolean; onChange: (v: boolean) => void; kbd?: string }) {
  return (
    <button role="switch" aria-checked={checked} onClick={() => onChange(!checked)}
      className="flex w-full items-center justify-between py-1 text-[12.5px] text-fog hover:text-paper">
      <span className="flex items-center gap-2">{label}{kbd && <kbd className="rounded bg-white/5 px-1 text-[10px] text-dim">{kbd}</kbd>}</span>
      <span className={`relative h-[18px] w-[32px] rounded-full transition-colors duration-200 ${checked ? 'bg-cyan/70' : 'bg-slate'}`}>
        <span className={`absolute top-[3px] h-3 w-3 rounded-full bg-paper transition-transform duration-200 ease-out-quint ${checked ? 'translate-x-[17px]' : 'translate-x-[3px]'}`} />
      </span>
    </button>
  )
}

export const fmt = {
  s: (v: number) => (v >= 90 ? `${Math.floor(v / 60)}m ${Math.round(v % 60).toString().padStart(2, '0')}s` : `${v.toFixed(v < 10 ? 1 : 0)} s`),
  kmh: (mps: number) => `${(mps * 3.6).toFixed(0)}`,
  m: (v: number) => (v >= 1000 ? `${(v / 1000).toFixed(2)} km` : `${v.toFixed(0)} m`),
  clock: (t: number) => {
    const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = Math.floor(t % 60)
    return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
  },
}
