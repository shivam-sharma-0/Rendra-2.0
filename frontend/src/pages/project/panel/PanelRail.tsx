import { Fragment, useRef, type KeyboardEvent } from 'react'
import { cx } from '../../../lib/cx'
import { Ico } from '../../../rendra/parts'
import type { Tab } from '../workspaceTypes'
import { PANEL_TAB_GROUPS } from './tabs'

const TABS = PANEL_TAB_GROUPS.flat()
const EXPAND_ICON = 'M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M16 21h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3'
const COLLAPSE_ICON = 'M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7'

/**
 * The panel's vertical icon rail: one tab per view, grouped, with the expand toggle at the
 * bottom. A vertical tablist — arrow keys, Home and End move between views.
 */
export function PanelRail({ tab, onSelect, expanded, onToggleExpand }: {
  tab: Tab
  onSelect: (tab: Tab) => void
  /** The panel fills the workspace (chat hidden). */
  expanded: boolean
  onToggleExpand: () => void
}) {
  const refs = useRef<Partial<Record<Tab, HTMLButtonElement | null>>>({})
  // Reasoning is opened from Details, so Details stays selected while it shows.
  const current: Tab = tab === 'thinking' ? 'details' : tab

  const onKeyDown = (e: KeyboardEvent) => {
    const index = TABS.findIndex((t) => t.id === current)
    const next = { ArrowDown: index + 1, ArrowUp: index - 1, Home: 0, End: TABS.length - 1 }[e.key]
    if (next === undefined) return
    e.preventDefault()
    const target = TABS[(next + TABS.length) % TABS.length]
    onSelect(target.id)
    refs.current[target.id]?.focus()
  }

  const expandLabel = expanded ? 'Show chat' : 'Expand panel'

  return (
    <nav className="ws-rail" aria-label="Panel">
      <div className="ws-rail__tabs" role="tablist" aria-orientation="vertical" aria-label="Panel views" onKeyDown={onKeyDown}>
        {PANEL_TAB_GROUPS.map((group, i) => (
          <Fragment key={group[0].id}>
            {i > 0 && <span className="ws-rail__groove" aria-hidden />}
            {group.map((t) => {
              const selected = t.id === current
              return (
                <button
                  key={t.id}
                  ref={(el) => { refs.current[t.id] = el }}
                  type="button"
                  role="tab"
                  aria-selected={selected}
                  tabIndex={selected ? 0 : -1}
                  aria-label={t.label}
                  data-tip={t.label}
                  className={cx('ws-rail__btn', selected && 'is-active')}
                  onClick={() => onSelect(t.id)}
                >
                  <Ico d={t.icon} size={17} sw={2} />
                </button>
              )
            })}
          </Fragment>
        ))}
      </div>
      <button type="button" className="ws-rail__btn ws-rail__expand" aria-label={expandLabel} data-tip={expandLabel} onClick={onToggleExpand}>
        <Ico d={expanded ? COLLAPSE_ICON : EXPAND_ICON} size={16} sw={2} />
      </button>
    </nav>
  )
}
