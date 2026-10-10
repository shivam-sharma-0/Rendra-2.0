import { useState, type FormEvent } from 'react'
import { type ProjectRole } from '../../../api/projects'
import { useProjectMembers, useRemoveProjectMember, useShareProject, useUpdateProjectMemberRole } from '../../../api/queries'
import { AppIcon } from '../../../components/icons'
import { NeuAlert, NeuButton, NeuDialog, NeuSelect } from '../../../components/neu'
import { Spinner } from '../../../components/ui/States'
import './dialogs.css'

/** Invite people to the project by email and manage what each collaborator can do. */
export function ShareDialog({ projectId, onClose }: { projectId: string; onClose: () => void }) {
  const { data: members, isLoading } = useProjectMembers(projectId)
  const share = useShareProject(projectId)
  const updateRole = useUpdateProjectMemberRole(projectId)
  const removeMember = useRemoveProjectMember(projectId)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<ProjectRole>('VIEWER')
  const [error, setError] = useState('')
  const list = members ?? []

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!email.trim()) return
    setError('')
    try {
      await share.mutateAsync({ email: email.trim(), role })
      setEmail('')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not share the project.')
    }
  }

  return (
    <NeuDialog title="Share this project" description="Invite people by email to view or edit. Collaborators see it in their projects." onClose={onClose}>
      <form className="dl-invite" onSubmit={(e) => void submit(e)}>
        <input className="neu-input" type="email" placeholder="name@company.com" aria-label="Email address" value={email} onChange={(e) => setEmail(e.target.value)} />
        <NeuSelect aria-label="Access" value={role} onChange={(e) => setRole(e.target.value as ProjectRole)}>
          <option value="VIEWER">Can view</option>
          <option value="EDITOR">Can edit</option>
        </NeuSelect>
        <NeuButton type="submit" variant="primary" disabled={share.isPending || !email.trim()}>{share.isPending ? 'Sharing…' : 'Invite'}</NeuButton>
      </form>
      {error && <NeuAlert>{error}</NeuAlert>}

      <section className="dl-section">
        <h3>People with access</h3>
        {isLoading
          ? <div className="dl-center"><Spinner /></div>
          : list.length
            ? (
              <ul className="dl-members">
                {list.map((m) => (
                  <li key={m.id}>
                    <span className="dl-avatar" aria-hidden>{(m.name || m.email || '?')[0].toUpperCase()}</span>
                    <span className="dl-member"><b>{m.name || m.email}</b>{m.name && <small>{m.email}</small>}</span>
                    <NeuSelect compact aria-label={`Access for ${m.email ?? 'member'}`} value={m.role} disabled={updateRole.isPending}
                      onChange={(e) => updateRole.mutate({ memberId: m.id, role: e.target.value as ProjectRole })}>
                      <option value="VIEWER">Can view</option>
                      <option value="EDITOR">Can edit</option>
                      <option value="OWNER">Owner</option>
                    </NeuSelect>
                    <button type="button" className="dl-remove" onClick={() => removeMember.mutate(m.id)} disabled={removeMember.isPending}
                      aria-label={`Remove ${m.email ?? 'member'}`} title="Remove">
                      <AppIcon name="close" />
                    </button>
                  </li>
                ))}
              </ul>
            )
            : <p className="dl-empty">Not shared with anyone yet — invite someone above.</p>}
      </section>
    </NeuDialog>
  )
}
