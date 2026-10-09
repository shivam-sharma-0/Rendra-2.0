import type { ProjectType } from '../../../api/projects'

export type ModeOption = { id: ProjectType; label: string; desc: string; icon: string }

/** How Rendra handles a prompt: build it, plan it first, or just talk about it. */
export const MODES: readonly ModeOption[] = [
  { id: 'BUILD', label: 'Build', desc: 'Writes and runs code for your app', icon: 'M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.4-.6-.6-2.4z' },
  { id: 'PLAN', label: 'Plan', desc: 'Maps pages, data and steps before building', icon: 'M9 5h11M9 12h11M9 19h11M4 5h.01M4 12h.01M4 19h.01' },
  { id: 'CHAT', label: 'Chat', desc: 'Ask questions without changing code', icon: 'M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z' },
]
