import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useNotifications, useMarkAllNotificationsRead } from '../../api/queries'
import { notificationApi, type NotificationType } from '../../api/projects'
import { EmptyState, ErrorState, Spinner } from '../../components/ui/States'
import AppShell from '../AppShell'
import { relTime, Ico } from '../parts'

/**
 * Notifications — Figma "09". Real per-user feed (React Query) with All / Unread tabs and
 * "Mark all as read". Each item deep-links to its project when it has one.
 */

const META: Record<NotificationType, { icon: string; bg: string; fg: string }> = {
  BUILD_FINISHED: { icon: 'M20 6 9 17l-5-5', bg: '#E7F8EF', fg: '#0B6B45' },
  BUILD_FAILED: { icon: 'M18 6 6 18M6 6l12 12', bg: '#FDECEA', fg: '#B42318' },
  PROJECT_SHARED: { icon: 'M4 12v8h16v-8M16 6l-4-4-4 4M12 2v14', bg: '#F1EEFF', fg: '#3D30C9' },
  WORKSPACE_INVITE: { icon: 'M16 21v-2a4 4 0 0 0-8 0v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM19 8v6M22 11h-6', bg: '#FFF1EB', fg: '#A33511' },
  ROLE_CHANGED: { icon: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33', bg: '#E4F1F7', fg: '#135E84' },
}

export default function Notifications() {
  const nav = useNavigate()
  const { data, isLoading, isError, refetch } = useNotifications()
  const markAll = useMarkAllNotificationsRead()
  const [tab, setTab] = useState<'all' | 'unread'>('all')

  const all = useMemo(() => data ?? [], [data])
  const shown = tab === 'unread' ? all.filter((n) => !n.read) : all

  const open = async (id: string, projectId: string | null, read: boolean) => {
    if (!read) { try { await notificationApi.markRead(id) } catch { /* best-effort */ } void refetch() }
    if (projectId) nav(`/project/${projectId}`)
  }

  return (
    <AppShell>
      <div className="kap-page">
        <div className="kap-ph">
          <div><h1>Notifications</h1><p>Stay up to date with your projects and team.</p></div>
          <button type="button" className="kap-fbtn" onClick={() => markAll.mutate()} disabled={markAll.isPending || all.every((n) => n.read)}>
            Mark all as read
          </button>
        </div>

        <div className="kap-tabs" style={{ alignSelf: 'flex-start' }}>
          <button type="button" className={`kap-tab ${tab === 'all' ? 'on' : ''}`} onClick={() => setTab('all')}>All <span className="n">{all.length}</span></button>
          <button type="button" className={`kap-tab ${tab === 'unread' ? 'on' : ''}`} onClick={() => setTab('unread')}>Unread <span className="n">{all.filter((n) => !n.read).length}</span></button>
        </div>

        {isLoading ? (
          <div style={{ display: 'grid', placeItems: 'center', padding: 60 }}><Spinner /></div>
        ) : isError ? (
          <ErrorState message="We couldn't load your notifications." onRetry={() => void refetch()} />
        ) : shown.length === 0 ? (
          <EmptyState title={tab === 'unread' ? "You're all caught up" : 'No notifications yet'} hint="Activity on your projects and workspace will show up here." />
        ) : (
          <div className="kap-notif-list">
            {shown.map((n) => {
              const m = META[n.type] ?? META.PROJECT_SHARED
              return (
                <button key={n.id} type="button" className={`kap-notif ${n.read ? '' : 'unread'}`} onClick={() => void open(n.id, n.projectId, n.read)}>
                  <span className="kap-notif-icon" style={{ background: m.bg, color: m.fg }}><Ico d={m.icon} size={16} sw={2} /></span>
                  <span className="kap-notif-main">
                    <b>{n.title}</b>
                    {n.body && <span>{n.body}</span>}
                    <small>{relTime(n.createdAt)}</small>
                  </span>
                  {!n.read && <span className="kap-notif-dot" />}
                </button>
              )
            })}
          </div>
        )}
      </div>
    </AppShell>
  )
}
