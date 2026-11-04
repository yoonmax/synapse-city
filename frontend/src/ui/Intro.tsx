import { useEffect, useRef } from 'react'
import gsap from 'gsap'
import { useStore } from '../state/store'
import { introControl } from '../city/renderer/IntroDirector'
import { RUNS_IN_BROWSER } from '../network/client'

const WORD = 'Synapse City'
const SKIPPED = typeof location !== 'undefined' && new URLSearchParams(location.search).get('intro') === 'skip'

/** Wordmark title card shown over the opening camera move. Skippable; hidden under reduced motion. */
export function Intro() {
  const introDone = useStore((s) => s.introDone)
  const city = useStore((s) => s.city)
  const connection = useStore((s) => s.connection)
  const root = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!root.current || introDone) return
    const letters = root.current.querySelectorAll('[data-letter]')
    const ctx = gsap.context(() => {
      gsap.fromTo(letters, { opacity: 0, y: 14, filter: 'blur(6px)' }, { opacity: 1, y: 0, filter: 'blur(0px)', duration: 0.7, ease: 'power3.out', stagger: 0.035 })
      gsap.fromTo('[data-sub]', { opacity: 0 }, { opacity: 1, duration: 0.6, delay: 0.5 })
      gsap.fromTo('[data-rule]', { scaleX: 0 }, { scaleX: 1, duration: 1.4, ease: 'power3.inOut', delay: 0.25 })
    }, root)
    return () => ctx.revert()
  }, [introDone])

  if (SKIPPED) return null
  return (
    <div ref={root} className={`pointer-events-none absolute inset-0 z-20 flex flex-col items-center justify-center transition-[opacity,visibility] duration-700 ${introDone ? 'invisible opacity-0' : 'opacity-100'}`}>
      <h1 className="wordmark flex text-[clamp(30px,6vw,64px)] text-paper" aria-label={WORD}>
        {WORD.split('').map((ch, i) => <span key={i} data-letter aria-hidden className={ch === ' ' ? 'w-[0.35em]' : ''}>{ch}</span>)}
      </h1>
      <div data-rule className="mt-4 h-px w-[min(420px,70vw)] origin-left bg-gradient-to-r from-transparent via-cyan to-transparent" />
      <p data-sub className="mt-4 text-[13px] text-fog">
        {connection === 'closed' ? (RUNS_IN_BROWSER ? 'The simulation could not start in this browser' : 'Waiting for the Java simulation server on port 8080') : city ? `Initialising ${city.name}` : 'Connecting to the simulation'}
      </p>
      {!introDone && city && (
        <button onClick={() => introControl.skip()} className="pointer-events-auto absolute bottom-8 right-8 rounded-md px-3 py-2 text-[12.5px] text-fog ring-1 ring-hair hover:text-paper hover:ring-slate">
          Skip intro
        </button>
      )}
    </div>
  )
}
