import { useState } from 'react'
import { type ProjectRole } from '../../../api/projects'
import { useProjectMembers, useRemoveProjectMember, useShareProject, useUpdateProjectMemberRole } from '../../../api/queries'

export function ShareDialog({ projectId, onClose }: { projectId: string; onClose: () => void }) {
  const { data: members, isLoading } = useProjectMembers(projectId)
  const share = useShareProject(projectId)
  const updateRole = useUpdateProjectMemberRole(projectId)
  const removeMember = useRemoveProjectMember(projectId)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<ProjectRole>('VIEWER')
  const [error, setError] = useState('')
  const list = members ?? []

  const submit = async () => {
    if (!email.trim()) return
    setError('')
    try { await share.mutateAsync({ email: email.trim(), role }); setEmail('') }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not share the project.') }
  }

  return (
    <div className="pw-modal-scrim" onClick={onClose}>
      <div className="pw-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Share this project">
        <header><b>Share this project</b><button className="pw-modal-x" onClick={onClose} aria-label="Close">✕</button></header>
        <p className="pw-modal-sub">Invite people by email to view or edit. Collaborators see it in their projects.</p>
        <div className="pw-share-invite">
          <input type="email" placeholder="name@company.com" value={email} onChange={(e) => setEmail(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void submit() } }} />
          <select value={role} onChange={(e) => setRole(e.target.value as ProjectRole)}>
            <option value="VIEWER">Can view</option><option value="EDITOR">Can edit</option>
          </select>
          <button className="pw-share-send" onClick={() => void submit()} disabled={share.isPending}>{share.isPending ? 'Sharing…' : 'Share'}</button>
        </div>
        {error && <p className="pw-error">{error}</p>}
        <div className="pw-share-list">
          {isLoading ? <p className="pw-loading">Loading…</p>
            : list.length ? list.map((m) => (
              <div className="pw-share-row" key={m.id}>
                <span className="pw-share-avatar">{(m.name || m.email || '?')[0].toUpperCase()}</span>
                <span className="pw-share-id"><b>{m.name || m.email}</b><small>{m.email}</small></span>
                <select value={m.role} onChange={(e) => updateRole.mutate({ memberId: m.id, role: e.target.value as ProjectRole })} disabled={updateRole.isPending}>
                  <option value="VIEWER">Can view</option><option value="EDITOR">Can edit</option><option value="OWNER">Owner</option>
                </select>
                <button className="pw-share-remove" onClick={() => removeMember.mutate(m.id)} disabled={removeMember.isPending} aria-label={`Remove ${m.email ?? 'member'}`}>✕</button>
              </div>
            )) : <p className="pw-share-empty">Not shared with anyone yet — invite someone above.</p>}
        </div>
      </div>
    </div>
  )
}
