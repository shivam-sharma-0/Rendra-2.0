import type { NotificationType } from '../../api/projects'
import type { AppIconName } from '../../components/icons'
import './notification-tones.css'

export type NotificationStyle = { icon: AppIconName; tone: 'ok' | 'bad' | 'share' | 'invite' | 'role' }

const STYLES: Record<NotificationType, NotificationStyle> = {
  BUILD_FINISHED: { icon: 'check', tone: 'ok' },
  BUILD_FAILED: { icon: 'close', tone: 'bad' },
  PROJECT_SHARED: { icon: 'share', tone: 'share' },
  WORKSPACE_INVITE: { icon: 'userPlus', tone: 'invite' },
  ROLE_CHANGED: { icon: 'gear', tone: 'role' },
}

/** Icon and colour tone (`nt-tone--<tone>`) for a notification type (the bell menu and the Notifications screen). */
export function notificationStyle(type: string): NotificationStyle {
  return STYLES[type as NotificationType] ?? STYLES.PROJECT_SHARED
}
