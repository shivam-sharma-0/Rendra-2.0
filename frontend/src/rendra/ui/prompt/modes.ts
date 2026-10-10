import type { ProjectType } from '../../../api/projects'
import type { AppIconName } from '../../../components/icons'

export type ModeOption = { id: ProjectType; label: string; desc: string; icon: AppIconName }

/** How Rendra handles a prompt: build it, plan it first, or just talk about it. */
export const MODES: readonly ModeOption[] = [
  { id: 'BUILD', label: 'Build', desc: 'Writes and runs code for your app', icon: 'wrench' },
  { id: 'PLAN', label: 'Plan', desc: 'Maps pages, data and steps before building', icon: 'listBullets' },
  { id: 'CHAT', label: 'Chat', desc: 'Ask questions without changing code', icon: 'chat' },
]
