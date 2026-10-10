import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'

/**
 * Where the panel's view tabs are: in the header — icons, with the current view's name shown once
 * `named` (it is briefly off while the tabs move) — or in the vertical rail.
 */
export type TabsLayout = { placement: 'rail' } | { placement: 'header'; named: boolean }

/** Header padding, the gaps around the centre and the header-only expand button. */
const HEADER_EXTRA = 28 + 20 + 44
/**
 * Room for the preview's controls (size button + the address at its minimum width), kept free on
 * every view so switching views never moves the tabs between the header and the rail.
 */
const CENTER_RESERVE = 34 + 10 + 150
/** Width of a header tab without its name (see .ws-tabs--horizontal .ws-tab). */
const ICON_TAB = 32 // 8px padding each side + the 16px icon
/** Width band around the switch point, so dragging near it does not flip back and forth. */
const HYSTERESIS = 20
/**
 * The name and the icons never move at once: coming back from the rail the name shows after the
 * icons have landed; going to the rail it folds first and the icons drop after.
 */
const NAME_MS = 420

const RAIL: TabsLayout = { placement: 'rail' }
const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

/**
 * How the tabs settle into their new place, one after another: a short drop into the rail, a
 * short rise into the header — never travelling across the panel.
 */
const ENTER = {
  rail: 'translateY(-18px)',
  header: 'translateY(14px)',
} as const

/**
 * Width the header strip needs: every tab as an icon plus the longest name, so the decision is the
 * same whichever view is current.
 */
function stripWidth(measure: HTMLElement) {
  const names = [...measure.querySelectorAll<HTMLElement>('[data-measure-tab]')].map((el) => el.offsetWidth - ICON_TAB)
  return measure.scrollWidth - names.reduce((a, b) => a + b, 0) + Math.max(0, ...names)
}

/**
 * Puts the panel's tabs in the header when the strip fits beside the actions (and the preview
 * controls), otherwise in the vertical rail. In the header only the current view shows its name.
 * Moving to the rail, the name folds first and then the tabs drop in one after another; moving
 * back, the tabs rise in one after another and then the name unfolds.
 *
 * `panelRef` is the panel card; `measureRef` an invisible fully labelled copy of the tabs;
 * `actionsRef` the header's action buttons.
 */
export function usePanelTabsPlacement(
  panelRef: RefObject<HTMLElement | null>,
  measureRef: RefObject<HTMLElement | null>,
  actionsRef: RefObject<HTMLElement | null>,
): TabsLayout {
  const [layout, setLayout] = useState<TabsLayout>(RAIL)
  const current = useRef<TabsLayout>(RAIL)
  const entering = useRef<TabsLayout['placement'] | null>(null)
  const timer = useRef<number | undefined>(undefined)
  /** Which move the pending timer finishes. */
  const phase = useRef<'rising' | 'folding' | null>(null)
  const placed = useRef(false)

  useEffect(() => {
    const panel = panelRef.current
    if (!panel) return
    const apply = (next: TabsLayout) => {
      current.current = next
      setLayout(next)
    }
    const later = (step: () => void) => {
      window.clearTimeout(timer.current)
      timer.current = window.setTimeout(() => { timer.current = undefined; phase.current = null; step() }, reducedMotion() ? 0 : NAME_MS)
    }
    const update = () => {
      const measure = measureRef.current
      const actions = actionsRef.current?.offsetWidth
      if (!measure || actions === undefined || panel.clientWidth === 0) return
      const now = current.current
      const room = panel.clientWidth - actions - HEADER_EXTRA - CENTER_RESERVE - (now.placement === 'rail' ? HYSTERESIS : 0)
      const fits = stripWidth(measure) <= room

      // The first layout (page load) is applied as is, without animation.
      if (!placed.current) {
        placed.current = true
        apply(fits ? { placement: 'header', named: true } : RAIL)
        return
      }
      if (timer.current !== undefined) {
        // Mid-move. If the name is folding before a drop and there is room again, stay.
        if (phase.current === 'folding' && fits) {
          window.clearTimeout(timer.current)
          timer.current = undefined
          phase.current = null
          apply({ placement: 'header', named: true })
        }
        return
      }
      if (now.placement === 'rail') {
        if (!fits) return
        entering.current = 'header'
        apply({ placement: 'header', named: false })
        phase.current = 'rising'
        later(() => { if (current.current.placement === 'header') apply({ placement: 'header', named: true }) })
        return
      }
      if (fits) return
      // Fold the name, then drop.
      apply({ placement: 'header', named: false })
      phase.current = 'folding'
      later(() => { entering.current = 'rail'; apply(RAIL) })
    }

    const observer = new ResizeObserver(update)
    observer.observe(panel)
    if (actionsRef.current) observer.observe(actionsRef.current)
    void document.fonts?.ready.then(update)
    return () => {
      observer.disconnect()
      window.clearTimeout(timer.current)
      timer.current = undefined
      phase.current = null
    }
  }, [panelRef, measureRef, actionsRef])

  // After the tabs re-render in their new place, settle each into it, one after another, with a
  // soft bounce — the same short motion both ways.
  useLayoutEffect(() => {
    const target = entering.current
    entering.current = null
    const panel = panelRef.current
    if (!target || !panel || reducedMotion()) return
    panel.querySelectorAll<HTMLElement>('[data-panel-tab]').forEach((el, i) => {
      el.animate(
        [{ transform: ENTER[target], opacity: 0 }, { opacity: 1, offset: 0.4 }, { transform: 'none', opacity: 1 }],
        { duration: 460, delay: i * 50, easing: 'cubic-bezier(.3, 1.25, .55, 1)', fill: 'backwards' },
      )
    })
  }, [layout.placement, panelRef])

  return layout
}
