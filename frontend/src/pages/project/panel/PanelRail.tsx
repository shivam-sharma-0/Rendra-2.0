import type { Tab } from '../workspaceTypes'
import { AppIcon } from '../../../components/icons'
import { PanelTabs } from './PanelTabs'

/** The panel's vertical icon rail (used while the header is too narrow for the tab strip), with the expand toggle at the bottom. */
export function PanelRail({ tab, onSelect, expanded, onToggleExpand }: {
  tab: Tab
  onSelect: (tab: Tab) => void
  /** The panel fills the workspace (chat hidden). */
  expanded: boolean
  onToggleExpand: () => void
}) {
  const expandLabel = expanded ? 'Show chat' : 'Expand panel'
  return (
    <nav className="ws-rail" aria-label="Panel">
      <PanelTabs tab={tab} onSelect={onSelect} orientation="vertical" />
      <button type="button" className="ws-tab ws-rail__expand" aria-label={expandLabel} data-tip={expandLabel} onClick={onToggleExpand}>
        <AppIcon name={expanded ? 'collapse' : 'expand'} size={20} />
      </button>
    </nav>
  )
}
