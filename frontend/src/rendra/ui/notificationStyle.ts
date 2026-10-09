import type { NotificationType } from '../../api/projects'
import { ICON } from '../icons'

export type NotificationStyle = { icon: string; bg: string; fg: string }

const STYLES: Record<NotificationType, NotificationStyle> = {
  BUILD_FINISHED: { icon: ICON.check, bg: '#E7F8EF', fg: '#0B6B45' },
  BUILD_FAILED: { icon: ICON.close, bg: '#FDECEA', fg: '#B42318' },
  PROJECT_SHARED: { icon: ICON.share, bg: '#F1EEFF', fg: '#3D30C9' },
  WORKSPACE_INVITE: { icon: ICON.userPlus, bg: '#FFF1EB', fg: '#A33511' },
  ROLE_CHANGED: { icon: ICON.settingsCog, bg: '#E4F1F7', fg: '#135E84' },
}

/** Icon and colours for a notification type (the bell menu and the Notifications screen). */
export function notificationStyle(type: string): NotificationStyle {
  return STYLES[type as NotificationType] ?? STYLES.PROJECT_SHARED
}
