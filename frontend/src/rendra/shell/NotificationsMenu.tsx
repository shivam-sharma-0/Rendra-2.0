import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { notificationApi } from '../../api/projects'
import { useMarkAllNotificationsRead, useNotifications, useUnreadCount } from '../../api/queries'
import { AppIcon } from '../../components/icons'
import { relTime } from '../parts'
import { notificationStyle } from '../ui/notificationStyle'
import type { PopoverControl } from './types'

/** Bell with unread badge and a popover listing notifications (all / unread). */
export default function NotificationsMenu({ open, onToggle, onClose }: PopoverControl) {
  const nav = useNavigate()
  const { data: unread = 0 } = useUnreadCount()
  const { data, isLoading, refetch } = useNotifications()
  const markAll = useMarkAllNotificationsRead()
  const [tab, setTab] = useState<'all' | 'unread'>('all')

  const all = data ?? []
  const unreadItems = all.filter((n) => !n.read)
  const shown = tab === 'unread' ? unreadItems : all

  const openItem = async (n: { id: string; projectId: string | null; read: boolean }) => {
    if (!n.read) {
      try { await notificationApi.markRead(n.id) } catch { /* best-effort */ }
      void refetch()
    }
    onClose()
    if (n.projectId) nav(`/project/${n.projectId}`)
  }

  return (
    <div className="kap-notif-wrap" style={{ position: 'relative' }}>
      <button type="button" className={`kap-bell ${open ? 'open' : ''}`} aria-expanded={open} onClick={onToggle}
        aria-label={`Notifications${unread > 0 ? ` (${unread} unread)` : ''}`}>
        <AppIcon name="bell" />
        {unread > 0 && <span className="dot">{unread > 9 ? '9+' : unread}</span>}
      </button>

      {open && (
        <div className="kap-notif-pop">
          <div className="kap-notif-pop-head">
            <div className="kap-notif-pop-title-wrap">
              <span className="kap-notif-pop-title">Notifications</span>
              {unread > 0 && <span className="kap-notif-pop-count">{unread} unread</span>}
            </div>
            {unread > 0 && (
              <button type="button" className="kap-notif-pop-mark" disabled={markAll.isPending}
                onClick={() => markAll.mutate(undefined, { onSuccess: () => void refetch() })}>
                <AppIcon name="check" /><span>Mark all read</span>
              </button>
            )}
          </div>

          <div className="kap-notif-pop-tabs">
            <button type="button" className={`kap-notif-pop-tab ${tab === 'all' ? 'on' : ''}`} onClick={() => setTab('all')}>
              All <span className="t-count">{all.length}</span>
            </button>
            <button type="button" className={`kap-notif-pop-tab ${tab === 'unread' ? 'on' : ''}`} onClick={() => setTab('unread')}>
              Unread <span className="t-count">{unreadItems.length}</span>
            </button>
          </div>

          <div className="kap-notif-pop-list">
            {isLoading ? (
              <div style={{ display: 'grid', placeItems: 'center', padding: '36px', color: 'var(--muted)', fontSize: '13px' }}>Loading notifications...</div>
            ) : shown.length === 0 ? (
              <div className="kap-notif-pop-empty">
                <div className="kap-notif-pop-empty-icon"><AppIcon name="bell" size={20} /></div>
                <div className="kap-notif-pop-empty-title">{tab === 'unread' ? "You're all caught up!" : 'No notifications yet'}</div>
                <div className="kap-notif-pop-empty-hint">
                  {tab === 'unread' ? 'No unread updates in your workspace.' : 'Activity on your projects and workspace will show up here.'}
                </div>
              </div>
            ) : (
              shown.map((n) => {
                const s = notificationStyle(n.type)
                return (
                  <button key={n.id} type="button" className={`kap-notif-pop-item ${n.read ? '' : 'unread'}`} onClick={() => void openItem(n)}>
                    <span className={`kap-notif-pop-icon nt-tone--${s.tone}`}><AppIcon name={s.icon} /></span>
                    <span className="kap-notif-pop-content">
                      <span className="kap-notif-pop-row">
                        <b className="kap-notif-pop-name">{n.title}</b>
                        <small className="kap-notif-pop-time">{relTime(n.createdAt)}</small>
                      </span>
                      {n.body && <span className="kap-notif-pop-body">{n.body}</span>}
                    </span>
                    {!n.read && <span className="kap-notif-pop-dot" />}
                  </button>
                )
              })
            )}
          </div>
        </div>
      )}
    </div>
  )
}
