import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { startProject, type ProjectType } from '../../../api/projects'
import { useAuth } from '../../../auth/AuthContext'
import { useWorkspace } from '../../../workspace/WorkspaceContext'
import { savePendingPrompt } from '../../pendingPrompt'

/**
 * Turns a landing-page prompt into a project. Signed-out visitors are sent to
 * sign up first; their prompt is kept and started right after they sign in.
 */
export function useStartProject() {
  const nav = useNavigate()
  const { isAuthenticated } = useAuth()
  const { selectedId } = useWorkspace()
  const [busy, setBusy] = useState(false)

  const start = async (text: string, mode: ProjectType) => {
    const prompt = text.trim()
    if (!prompt || busy) return
    if (!isAuthenticated) {
      savePendingPrompt({ prompt, mode })
      nav('/signup')
      return
    }
    setBusy(true)
    try {
      const project = await startProject(prompt, mode, selectedId ?? undefined)
      nav(`/project/${project.id}`)
    } finally {
      setBusy(false)
    }
  }

  /** "Start building" buttons: the dashboard when signed in, otherwise sign up. */
  const goToStart = () => nav(isAuthenticated ? '/dashboard' : '/signup')

  return { start, busy, isAuthenticated, goToStart }
}
