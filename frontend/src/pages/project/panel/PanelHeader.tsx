import { forwardRef, type ReactNode } from 'react'
import { NeuButton, NeuIconButton } from '../../../components/neu'
import { cx } from '../../../lib/cx'
import { snapTextWidth } from '../../../lib/snapTextWidth'
import type { Tab } from '../workspaceTypes'
import { AppIcon } from '../../../components/icons'
import { PANEL_TITLES } from './tabs'

type PanelHeaderProps = {
  tab: Tab
  /** The view tabs, when the header holds them (they replace the title). */
  tabs?: ReactNode
  /** The view's own controls, centred (the preview address and size). */
  center?: ReactNode
  /** Shown before the actions, e.g. the expand toggle when there is no rail. */
  lead?: ReactNode
  /** Always rendered and invisible: the labelled tabs, measured to decide where the tabs go. */
  measure: ReactNode
  /** Stop is shown while a run is active; Restore when the latest build can be rolled back. */
  run: { active: boolean; saving?: boolean; cancelling: boolean; onCancel: () => void; canRollback: boolean; rollingBack: boolean; onRollback: () => void }
  published: boolean
  onShare: () => void
  onPublish: () => void
}

/**
 * The panel's top bar: the view title (or the tabs), the view's controls in the middle, then the
 * project actions. As the panel narrows, CSS container queries hide the button labels and then the
 * title; the preview address always stays readable. The forwarded ref is the actions group, whose
 * width decides where the tabs fit.
 */
export const PanelHeader = forwardRef<HTMLDivElement, PanelHeaderProps>(function PanelHeader(
  { tab, tabs, center, lead, measure, run, published, onShare, onPublish },
  actionsRef,
) {
  const publishLabel = published ? 'Published' : 'Publish'
  return (
    <header className={cx('ws-phead', !!tabs && 'ws-phead--tabs')}>
      {measure}
      {tabs ?? <h2 key={tab} ref={snapTextWidth} className="ws-phead__title">{PANEL_TITLES[tab]}</h2>}

      <div className="ws-phead__center">{center}</div>

      {lead}
      <div className="ws-phead__actions" ref={actionsRef}>
        {run.active && (
          <NeuButton size="sm" onClick={run.onCancel} disabled={run.cancelling || run.saving}>{run.saving ? 'Saving...' : run.cancelling ? 'Stopping…' : 'Stop'}</NeuButton>
        )}
        {run.canRollback && (
          <NeuIconButton
            className="ws-phead__icon"
            aria-label={run.rollingBack ? 'Restoring the previous build…' : 'Restore the previous build'}
            title={run.rollingBack ? 'Restoring…' : 'Restore the previous build'}
            onClick={run.onRollback}
            disabled={run.rollingBack}
          >
            <AppIcon name="restore" size={16} />
          </NeuIconButton>
        )}
        <NeuButton size="sm" className="ws-phead__btn" onClick={onShare} aria-label="Share" title="Share">
          <AppIcon name="share" size={16} />
          <span ref={snapTextWidth} className="ws-phead__label">Share</span>
        </NeuButton>
        <NeuButton size="sm" variant="accent" className="ws-phead__btn ws-publish" onClick={onPublish} aria-label={publishLabel} title={publishLabel}>
          {published ? <span className="ws-publish__live" aria-hidden /> : <span className="ws-publish__icon"><AppIcon name="globe" size={16} /></span>}
          <span key={publishLabel} ref={snapTextWidth} className="ws-phead__label">{publishLabel}</span>
        </NeuButton>
      </div>
    </header>
  )
})
