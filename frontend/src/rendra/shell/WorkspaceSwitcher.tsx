import { Link } from 'react-router-dom'
import { useMembers } from '../../api/queries'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { ICON } from '../icons'
import { initials } from '../parts'
import ShellIcon from './ShellIcon'
import type { PopoverControl } from './types'
import UsageMeter from './UsageMeter'

const BADGE_COLOURS = ['#16151A', '#5B4CF5', '#9A6000', '#0B7F58', '#C92A66']

/** Current-workspace button with a popover: quick actions, credits, switching and Pro upsell. */
export default function WorkspaceSwitcher({ badgeLabel, open, onToggle, onClose }: PopoverControl & { badgeLabel: string }) {
  const { workspaces, selected, selectedId, setSelectedId } = useWorkspace()
  const name = selected?.name ?? 'Your workspace'

  return (
    <div className="kap-ws-wrap">
      <button type="button" className={`kap-ws ${open ? 'open' : ''}`} aria-expanded={open} onClick={onToggle}>
        <span className="kap-ws-badge">{initials(badgeLabel)[0]}</span>
        <span className="kap-ws-text" style={{ display: 'flex', flexDirection: 'column', flexGrow: 1, minWidth: 0 }}>
          <span className="kap-ws-name">{name}</span>
        </span>
        <span className={`kap-ws-chevron ${open ? 'open' : ''}`}><ShellIcon d={ICON.chevronDown} size={15} /></span>
      </button>

      {open && (
        <div className="kap-ws-pop">
          <div className="kap-ws-pop-header">
            <div className="kap-ws-pop-avatar"><ShellIcon d={ICON.upload} size={19} /></div>
            <div className="kap-ws-pop-info">
              <div className="kap-ws-pop-title">{name}</div>
              <PlanLine workspaceId={selectedId} />
            </div>
          </div>

          <div className="kap-ws-pop-actions">
            <Link to="/settings/members" className="kap-ws-btn" onClick={onClose}>
              <ShellIcon d={ICON.invite} size={15} /><span>Invite members</span>
            </Link>
            <Link to="/settings/members" className="kap-ws-btn" onClick={onClose}>
              <ShellIcon d={ICON.gear} size={15} /><span>Workspace settings</span>
            </Link>
          </div>

          <div className="kap-ws-divider" />

          <UsageMeter onNavigate={onClose} />

          {workspaces.length > 1 && (
            <>
              <div className="kap-ws-divider" />
              <div className="kap-ws-list">
                {workspaces.map((w, i) => (
                  <button key={w.id} type="button" className={`kap-ws-item ${w.id === selectedId ? 'active' : ''}`}
                    onClick={() => { setSelectedId(w.id); onClose() }}>
                    <span className="kap-ws-item-badge" style={{ background: BADGE_COLOURS[i % BADGE_COLOURS.length] }}>{initials(w.name)[0]}</span>
                    <span className="kap-ws-item-name">{w.name}</span>
                    {w.id === selectedId && <ShellIcon d={ICON.check} size={15} />}
                  </button>
                ))}
              </div>
            </>
          )}

          <div className="kap-ws-divider" />

          <Link to="/onboarding/workspace" className="kap-ws-new-link" onClick={onClose}>
            <ShellIcon d={ICON.plus} size={16} /><span>New workspace</span>
          </Link>

          <div className="kap-ws-divider" />

          <div className="kap-ws-footer">
            <div className="kap-ws-pro-tag"><ShellIcon d={ICON.bolt} size={15} /><span>Turn Pro</span></div>
            <Link to="/settings/billing-and-usage" className="kap-ws-upgrade-btn" onClick={onClose}>Upgrade</Link>
          </div>
        </div>
      )}
    </div>
  )
}

/** "Free plan · N members". Every workspace is on the free plan until billing exists. */
function PlanLine({ workspaceId }: { workspaceId: string | null }) {
  const { data: members } = useMembers(workspaceId)
  const count = members?.length
  return (
    <div className="kap-ws-pop-subtitle">
      Free plan{count !== undefined && ` • ${count} ${count === 1 ? 'member' : 'members'}`}
    </div>
  )
}
