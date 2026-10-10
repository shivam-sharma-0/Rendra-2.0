import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { memberApi, workspaceApi, type WorkspaceRole } from '../../../api/projects'
import { useAuth } from '../../../auth/AuthContext'
import { useWorkspace } from '../../../workspace/WorkspaceContext'
import { COLORS, type InviteRole, slugify } from './content'

export type Invite = { email: string; role: InviteRole }

/**
 * The three onboarding steps' state and actions: about you (role, goals), the workspace (created or
 * renamed when leaving step 2), and invites (sent on finish; one failed invite does not stop the
 * rest). The answers to step 1 are kept in localStorage (`rendra_persona`) for suggestions.
 */
export function useOnboarding() {
  const nav = useNavigate()
  const { user } = useAuth()
  const { selectedId, setSelectedId, reload } = useWorkspace()
  const firstName = user?.name?.split(' ')[0] || user?.email?.split('@')[0] || 'there'
  const initialName = user?.name ? `${firstName}'s Studio` : 'My Workspace'

  const [step, setStep] = useState(1)
  const [role, setRole] = useState('founder')
  const [goals, setGoals] = useState<string[]>(['saas'])
  const [workspaceName, setWorkspaceName] = useState(initialName)
  const [slug, setSlug] = useState(() => slugify(initialName))
  const [kind, setKind] = useState('team')
  const [color, setColor] = useState(COLORS[0].value)
  const [invites, setInvites] = useState<Invite[]>([{ email: '', role: 'Editor' }, { email: '', role: 'Editor' }, { email: '', role: 'Viewer' }])
  const [workspaceId, setWorkspaceId] = useState<string | null>(null)
  const [nameError, setNameError] = useState('')
  const [busy, setBusy] = useState(false)

  const filledInvites = useMemo(() => invites.filter((i) => i.email.trim()), [invites])

  const toggleGoal = (id: string) => setGoals((g) => (g.includes(id) ? g.filter((x) => x !== id) : [...g, id]))
  const renameWorkspace = (value: string) => {
    setWorkspaceName(value)
    setSlug(slugify(value))
    if (nameError) setNameError('')
  }
  const updateInvite = (index: number, change: Partial<Invite>) => setInvites((rows) => rows.map((row, i) => (i === index ? { ...row, ...change } : row)))
  const addInvite = () => setInvites((rows) => [...rows, { email: '', role: 'Editor' }])

  const savePersona = () => {
    try { localStorage.setItem('rendra_persona', JSON.stringify({ role, goals, kind, color, slug })) } catch { /* storage unavailable */ }
  }

  const ensureWorkspace = async () => {
    if (!workspaceName.trim()) {
      setNameError('Enter a workspace name')
      return false
    }
    setBusy(true)
    try {
      if (workspaceId) {
        await workspaceApi.rename(workspaceId, workspaceName.trim())
      } else {
        const created = await workspaceApi.create(workspaceName.trim())
        setWorkspaceId(created.id)
        setSelectedId(created.id)
      }
      await reload()
      return true
    } catch (err) {
      setNameError(err instanceof Error ? err.message : 'Could not create the workspace')
      return false
    } finally {
      setBusy(false)
    }
  }

  const next = async () => {
    if (step === 1) setStep(2)
    else if (step === 2 && await ensureWorkspace()) setStep(3)
  }
  const back = () => setStep((s) => Math.max(1, s - 1))

  const finish = async () => {
    const id = workspaceId ?? selectedId
    setBusy(true)
    try {
      if (id) {
        for (const invite of filledInvites) {
          try {
            const member = await memberApi.invite(id, invite.email.trim())
            if (invite.role === 'Admin') await memberApi.updateRole(id, member.id, 'ADMIN' as WorkspaceRole)
          } catch { /* skip a single failed invite (e.g. already a member) and continue with the rest */ }
        }
      }
      savePersona()
      nav('/dashboard')
    } finally {
      setBusy(false)
    }
  }
  const skip = () => {
    savePersona()
    nav('/dashboard')
  }

  const initials = (user?.name || user?.email || 'You').split(/\s|@/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase()

  return {
    firstName, initials, step, busy,
    role, setRole, goals, toggleGoal,
    workspaceName, renameWorkspace, slug, setSlug: (value: string) => setSlug(slugify(value)), nameError, kind, setKind, color, setColor,
    invites, filledInvites, updateInvite, addInvite,
    next, back, finish, skip,
  }
}

export type OnboardingState = ReturnType<typeof useOnboarding>
