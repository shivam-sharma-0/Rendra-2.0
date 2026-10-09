import type { AppNotification } from '../../../api/projects'
import { cx } from '../../../lib/cx'
import { Ico, relTime } from '../../parts'
import { notificationStyle } from '../../ui/notificationStyle'

/** One notification. Opening it marks it read and goes to its project when it has one. */
export default function NotificationRow({ notification: n, onOpen }: { notification: AppNotification; onOpen: () => void }) {
  const style = notificationStyle(n.type)
  return (
    <button type="button" className={cx('nt-row', !n.read && 'is-unread')} onClick={onOpen}>
      <span className="nt-row__icon" style={{ background: style.bg, color: style.fg }} aria-hidden><Ico d={style.icon} size={16} sw={2} /></span>
      <span className="nt-row__body">
        <b>{n.title}</b>
        {n.body && <span>{n.body}</span>}
        <small>{relTime(n.createdAt)}</small>
      </span>
      {!n.read && <span className="nt-row__dot" aria-label="Unread" />}
    </button>
  )
}
