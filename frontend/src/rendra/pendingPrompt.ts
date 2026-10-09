import type { ProjectType } from '../api/projects'

const PROMPT_KEY = 'rendra_pending_prompt'
const MODE_KEY = 'rendra_pending_mode'

/** A prompt a signed-out visitor typed on the landing page, kept until they have signed in. */
export type PendingPrompt = { prompt: string; mode: ProjectType }

export function savePendingPrompt({ prompt, mode }: PendingPrompt): void {
  sessionStorage.setItem(PROMPT_KEY, prompt)
  sessionStorage.setItem(MODE_KEY, mode)
}

/** Returns the pending prompt and clears it, so it starts a project only once. */
export function takePendingPrompt(): PendingPrompt | null {
  const prompt = sessionStorage.getItem(PROMPT_KEY)
  const mode = (sessionStorage.getItem(MODE_KEY) || 'BUILD') as ProjectType
  sessionStorage.removeItem(PROMPT_KEY)
  sessionStorage.removeItem(MODE_KEY)
  return prompt ? { prompt, mode } : null
}
