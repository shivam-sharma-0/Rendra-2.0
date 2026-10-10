import { forwardRef, Fragment, useRef, type KeyboardEvent } from 'react'
import { cx } from '../../../lib/cx'
import { snapTextWidth } from '../../../lib/snapTextWidth'
import { useSlidingThumb } from '../../../lib/useSlidingThumb'
import type { Tab } from '../workspaceTypes'
import { AppIcon } from '../../../components/icons'
import { PANEL_TAB_GROUPS, PANEL_TABS } from './tabs'

export type TabsOrientation = 'vertical' | 'horizontal'

const TABS = PANEL_TABS

type PanelTabsProps = {
  tab: Tab
  onSelect: (tab: Tab) => void
  orientation: TabsOrientation
  /** Horizontal only: show the current view's name (the other tabs are always icons). */
  showName?: boolean
}

/**
 * The panel's views as a tablist: icons in the vertical rail, a grouped strip of icons in the header
 * where the current view also shows its name (the others' names are tooltips). Each tab carries
 * `data-panel-tab` so the placement hook can animate it between the two. Arrow keys, Home and End
 * move between views.
 */
export function PanelTabs({ tab, onSelect, orientation, showName = true }: PanelTabsProps) {
  const refs = useRef<Partial<Record<Tab, HTMLButtonElement | null>>>({})
  const listRef = useRef<HTMLDivElement>(null)
  const thumbRef = useRef<HTMLSpanElement>(null)
  // Reasoning is opened from Details, so Details stays selected while it shows.
  const current: Tab = tab === 'thinking' ? 'details' : tab
  const vertical = orientation === 'vertical'
  // The current tab's pill slides from tab to tab instead of jumping.
  useSlidingThumb(listRef, thumbRef, `[data-panel-tab="${current}"]`)

  const onKeyDown = (e: KeyboardEvent) => {
    const index = TABS.findIndex((t) => t.id === current)
    const [back, forward] = vertical ? ['ArrowUp', 'ArrowDown'] : ['ArrowLeft', 'ArrowRight']
    const next = { [forward]: index + 1, [back]: index - 1, Home: 0, End: TABS.length - 1 }[e.key]
    if (next === undefined) return
    e.preventDefault()
    const target = TABS[(next + TABS.length) % TABS.length]
    onSelect(target.id)
    refs.current[target.id]?.focus()
  }

  return (
    <div ref={listRef} className={cx('ws-tabs', `ws-tabs--${orientation}`)} role="tablist" aria-orientation={orientation} aria-label="Panel views" onKeyDown={onKeyDown}>
      <span ref={thumbRef} className="ws-tabs__thumb" aria-hidden />
      {PANEL_TAB_GROUPS.map((group, g) => (
        <Fragment key={group[0].id}>
          {g > 0 && <span className="ws-tabs__groove" aria-hidden />}
          {group.map((t) => {
            const selected = t.id === current
            const folded = vertical || !selected || !showName
            return (
              <button
                key={t.id}
                ref={(el) => { refs.current[t.id] = el }}
                type="button"
                role="tab"
                aria-selected={selected}
                tabIndex={selected ? 0 : -1}
                aria-label={t.label}
                data-panel-tab={t.id}
                data-tip={folded ? t.label : undefined}
                className={cx('ws-tab', selected && 'is-active', folded && 'is-folded')}
                onClick={() => onSelect(t.id)}
              >
                <AppIcon name={t.icon} size={vertical ? 20 : 16} />
                {!vertical && <span ref={snapTextWidth} className="ws-tab__label">{t.label}</span>}
              </button>
            )
          })}
        </Fragment>
      ))}
    </div>
  )
}

/** An invisible copy of the fully labelled header tabs, kept only to measure each tab's width. */
export const PanelTabsMeasure = forwardRef<HTMLDivElement>(function PanelTabsMeasure(_, ref) {
  return (
    <div ref={ref} className="ws-tabs ws-tabs--horizontal ws-tabs--measure" aria-hidden inert>
      {PANEL_TAB_GROUPS.map((group, g) => (
        <Fragment key={group[0].id}>
          {g > 0 && <span className="ws-tabs__groove" />}
          {group.map((t) => (
            <span key={t.id} className="ws-tab" data-measure-tab={t.id}><AppIcon name={t.icon} size={16} /><span ref={snapTextWidth} className="ws-tab__label">{t.label}</span></span>
          ))}
        </Fragment>
      ))}
    </div>
  )
})
