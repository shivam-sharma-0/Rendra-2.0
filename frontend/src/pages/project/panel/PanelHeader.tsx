import { NeuButton, NeuIconButton, NeuSegmentedControl } from '../../../components/neu'
import { Ico } from '../../../rendra/parts'
import type { Device, Tab } from '../workspaceTypes'
import { PANEL_TITLES, WS_DEVICES } from './tabs'

const LOCK_ICON = 'M4 11h16v10H4zM8 11V7a4 4 0 0 1 8 0v4'
const RELOAD_ICON = 'M21 12a9 9 0 1 1-2.6-6.4L21 8M21 3v5h-5'
const RESTORE_ICON = 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5'
const SHARE_ICON = 'M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13'
const GLOBE_ICON = 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM2 12h20M12 2a15 15 0 0 1 0 20M12 2a15 15 0 0 0 0 20'

const DEVICE_OPTIONS = WS_DEVICES.map((d) => ({ value: d.id, title: d.label, label: <Ico d={d.icon} size={15} sw={2} /> }))

type PanelHeaderProps = {
  tab: Tab
  device: Device
  onDevice: (device: Device) => void
  onReloadPreview: () => void
  /** Stop is shown while a run is active; Restore when the latest build can be rolled back. */
  run: { active: boolean; cancelling: boolean; onCancel: () => void; canRollback: boolean; rollingBack: boolean; onRollback: () => void }
  published: boolean
  onShare: () => void
  onPublish: () => void
}

/**
 * The panel's top bar: the view's title, preview controls on Preview, then the project actions.
 * As the panel narrows, CSS container queries hide the address text and then the button labels.
 */
export function PanelHeader({ tab, device, onDevice, onReloadPreview, run, published, onShare, onPublish }: PanelHeaderProps) {
  const publishLabel = published ? 'Published' : 'Publish'
  return (
    <header className="ws-phead">
      <h2 className="ws-phead__title">{PANEL_TITLES[tab]}</h2>

      <div className="ws-phead__center">
        {tab === 'preview' && (
          <>
            <div className="ws-url">
              <Ico d={LOCK_ICON} size={13} sw={2.2} />
              <span>/preview</span>
              <button type="button" aria-label="Reload preview" title="Reload preview" onClick={onReloadPreview}>
                <Ico d={RELOAD_ICON} size={14} sw={2.2} />
              </button>
            </div>
            <NeuSegmentedControl className="ws-devices" options={DEVICE_OPTIONS} value={device} onChange={onDevice} aria-label="Preview size" />
          </>
        )}
      </div>

      <div className="ws-phead__actions">
        {run.active && (
          <NeuButton size="sm" onClick={run.onCancel} disabled={run.cancelling}>{run.cancelling ? 'Stopping…' : 'Stop'}</NeuButton>
        )}
        {run.canRollback && (
          <NeuIconButton
            className="ws-phead__icon"
            aria-label={run.rollingBack ? 'Restoring the previous build…' : 'Restore the previous build'}
            title={run.rollingBack ? 'Restoring…' : 'Restore the previous build'}
            onClick={run.onRollback}
            disabled={run.rollingBack}
          >
            <Ico d={RESTORE_ICON} size={15} sw={2} />
          </NeuIconButton>
        )}
        <NeuButton size="sm" className="ws-phead__btn" onClick={onShare} aria-label="Share" title="Share">
          <Ico d={SHARE_ICON} size={14} sw={2} />
          <span className="ws-phead__label">Share</span>
        </NeuButton>
        <NeuButton size="sm" variant="accent" className="ws-phead__btn ws-publish" onClick={onPublish} aria-label={publishLabel} title={publishLabel}>
          {published ? <span className="ws-publish__live" aria-hidden /> : <span className="ws-publish__icon"><Ico d={GLOBE_ICON} size={14} sw={2} /></span>}
          <span className="ws-phead__label">{publishLabel}</span>
        </NeuButton>
      </div>
    </header>
  )
}
