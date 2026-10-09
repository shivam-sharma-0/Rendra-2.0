import { startProject } from '../../../api/projects'
import { takePendingPrompt } from '../../pendingPrompt'
import type { AuthMode } from './authSchema'

/**
 * Where to send the user after a successful login or signup. A prompt typed on
 * the landing page before signing in is turned into a project first.
 */
export async function resolvePostAuthPath(mode: AuthMode): Promise<string> {
  const pending = takePendingPrompt()
  if (pending) {
    const project = await startProject(pending.prompt, pending.mode)
    return `/project/${project.id}`
  }
  return mode === 'signup' ? '/onboarding/about-you' : '/dashboard'
}
