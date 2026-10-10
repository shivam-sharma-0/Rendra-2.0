import { APP_ICONS, type AppIconName } from './appIcons'

/**
 * The sizes these icon sets are designed for. In between (15, 17, 18, 19…) their edges fall between
 * pixels and look soft, so other sizes are not allowed. Place icons on whole pixels too: centred in
 * a box, the box and the icon should differ by an even number.
 */
export type AppIconSize = 16 | 20 | 24

/** An icon from the app's icon set (see appIcons.ts), drawn in the current text colour. */
export default function AppIcon({ name, size = 16 }: { name: AppIconName; size?: AppIconSize }) {
  const icon = APP_ICONS[name]
  return (
    <svg width={size} height={size} viewBox={`0 0 ${icon.size} ${icon.size}`} fill="currentColor" aria-hidden focusable="false">
      {icon.paths.map((d) => <path key={d} d={d} />)}
    </svg>
  )
}
