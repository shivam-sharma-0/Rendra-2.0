import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { notificationApi, type AppNotification } from '../../../api/projects'
import { useMarkAllNotificationsRead, useNotifications } from '../../../api/queries'
import { NeuButton, NeuPanel, NeuSegmentedControl } from '../../../components/neu'
import { EmptyState, ErrorState, Spinner } from '../../../components/ui/States'
import AppShell from '../../AppShell'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import PageHeader from '../../ui/PageHeader'
import NotificationRow from './NotificationRow'
import './notifications.css'

type Tab = 'all' | 'unread'

/** The signed-in user's notification feed, with All / Unread and "Mark all as read". */
export default function Notifications() {
  const nav = useNavigate()
  const { data, isLoading, isError, refetch } = useNotifications()
  const markAll = useMarkAllNotificationsRead()
  const [tab, setTab] = useState<Tab>('all')

  const all = useMemo(() => data ?? [], [data])
  const unread = all.filter((n) => !n.read)
  const shown = tab === 'unread' ? unread : all

  const open = async (n: AppNotification) => {
    if (!n.read) {
      try { await notificationApi.markRead(n.id) } catch { /* best-effort */ }
      void refetch()
    }
    if (n.projectId) nav(`/project/${n.projectId}`)
  }

  const tabs = [
    { value: 'all' as const, label: <>All <span className="nt-count">{all.length}</span></> },
    { value: 'unread' as const, label: <>Unread <span className="nt-count">{unread.length}</span></> },
  ]

  return (
    <AppShell surface="plain">
      <div className="page">
        <div className="page__inner">
          <PageHeader
            title="Notifications"
            description="Stay up to date with your projects and team."
            action={
              <NeuButton size="sm" pill disabled={markAll.isPending || unread.length === 0} onClick={() => markAll.mutate()}>
                <Ico d={ICON.check} size={13} sw={2.4} />Mark all as read
              </NeuButton>
            }
          />
          <NeuSegmentedControl aria-label="Notifications" className="nt-tabs" options={tabs} value={tab} onChange={setTab} />

          {isLoading ? (
            <div className="page__state"><Spinner /></div>
          ) : isError ? (
            <ErrorState message="We couldn't load your notifications." onRetry={() => void refetch()} />
          ) : shown.length === 0 ? (
            <EmptyState title={tab === 'unread' ? "You're all caught up" : 'No notifications yet'} hint="Activity on your projects and workspace will show up here." />
          ) : (
            <NeuPanel size="sm" className="nt-list" role="list">
              {shown.map((n) => <div role="listitem" key={n.id}><NotificationRow notification={n} onOpen={() => void open(n)} /></div>)}
            </NeuPanel>
          )}
        </div>
      </div>
    </AppShell>
  )
}
