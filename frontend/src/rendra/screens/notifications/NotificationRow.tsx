import type { AppNotification } from '../../../api/projects'
import { AppIcon } from '../../../components/icons'
import { cx } from '../../../lib/cx'
import { relTime } from '../../parts'
import { notificationStyle } from '../../ui/notificationStyle'

/** One notification. Opening it marks it read and goes to its project when it has one. */
export default function NotificationRow({ notification: n, onOpen }: { notification: AppNotification; onOpen: () => void }) {
  const style = notificationStyle(n.type)
  return (
    <button type="button" className={cx('nt-row', !n.read && 'is-unread')} onClick={onOpen}>
      <span className={`nt-row__icon nt-tone--${style.tone}`} aria-hidden><AppIcon name={style.icon} /></span>
      <span className="nt-row__body">
        <b>{n.title}</b>
        {n.body && <span>{n.body}</span>}
        <small>{relTime(n.createdAt)}</small>
      </span>
      {!n.read && <span className="nt-row__dot" aria-label="Unread" />}
    </button>
  )
}
