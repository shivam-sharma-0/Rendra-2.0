import { useRef, type ComponentProps, type ReactNode } from 'react'
import { cx } from '../../../lib/cx'
import type { Device, Tab } from '../workspaceTypes'
import { PanelHeader } from './PanelHeader'
import { AppIcon } from '../../../components/icons'
import { PanelRail } from './PanelRail'
import { PanelTabs, PanelTabsMeasure } from './PanelTabs'
import { PreviewControls } from './PreviewControls'
import { usePanelTabsPlacement } from './usePanelTabsPlacement'

type WorkspacePanelProps = {
  tab: Tab
  onSelectTab: (tab: Tab) => void
  /** Chat-only layout: the panel is collapsed. */
  hidden: boolean
  /** The panel fills the workspace (chat hidden). */
  expanded: boolean
  onToggleExpand: () => void
  device: Device
  onDevice: (device: Device) => void
  onReloadPreview: () => void
  /** The address the preview shows (for "open in a new tab"). */
  previewUrl: string | null
  run: ComponentProps<typeof PanelHeader>['run']
  published: boolean
  onShare: () => void
  onPublish: () => void
  /** The selected view. */
  children: ReactNode
}

/**
 * The right-hand panel card. Its view tabs sit in the header (icons, with the current view's name)
 * when they fit, otherwise in a vertical rail; they animate between the two as the panel resizes. The preview's address and size stay in the header either way.
 */
export function WorkspacePanel({ tab, onSelectTab, hidden, expanded, onToggleExpand, device, onDevice, onReloadPreview, previewUrl, run, published, onShare, onPublish, children }: WorkspacePanelProps) {
  const panelRef = useRef<HTMLElement>(null)
  const measureRef = useRef<HTMLDivElement>(null)
  const actionsRef = useRef<HTMLDivElement>(null)
  const tabs = usePanelTabsPlacement(panelRef, measureRef, actionsRef)
  const inHeader = tabs.placement === 'header'
  const preview = tab === 'preview' && <PreviewControls device={device} onDevice={onDevice} onReload={onReloadPreview} url={previewUrl} />
  const expandLabel = expanded ? 'Show chat' : 'Expand panel'

  return (
    <section ref={panelRef} className={cx('ws-panel', hidden && 'ws-panel--hidden', inHeader && 'ws-panel--tabs-in-header')}>
      {!inHeader && <PanelRail tab={tab} onSelect={onSelectTab} expanded={expanded} onToggleExpand={onToggleExpand} />}
      <div className="ws-panel__main">
        <PanelHeader
          ref={actionsRef}
          tab={tab}
          tabs={tabs.placement === 'header' ? <PanelTabs tab={tab} onSelect={onSelectTab} orientation="horizontal" showName={tabs.named} /> : undefined}
          center={preview}
          lead={inHeader && (
            <button type="button" className="ws-flat-btn" aria-label={expandLabel} title={expandLabel} onClick={onToggleExpand}>
              <AppIcon name={expanded ? 'collapse' : 'expand'} size={16} />
            </button>
          )}
          measure={<PanelTabsMeasure ref={measureRef} />}
          run={run}
          published={published}
          onShare={onShare}
          onPublish={onPublish}
        />
        <div className="ws-screen">
          {children}
        </div>
      </div>
    </section>
  )
}
