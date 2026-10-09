import { Ico } from '../parts'

/** The shell's icons use a lighter stroke than the rest of the app. */
export default function ShellIcon({ d, size = 18 }: { d: string; size?: number }) {
  return <Ico d={d} size={size} sw={1.6} />
}
