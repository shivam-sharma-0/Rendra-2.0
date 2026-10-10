import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { inviteApi } from '../../../api/projects'
import { useAuth } from '../../../auth/AuthContext'
import { AppIcon } from '../../../components/icons'
import { NeuAlert, NeuButton } from '../../../components/neu'
import { Spinner } from '../../../components/ui/States'
import { useWorkspace } from '../../../workspace/WorkspaceContext'
import CenteredScreen from '../../ui/CenteredScreen'
import './invite.css'

const ROLE_TEXT = { OWNER: 'an owner', ADMIN: 'an admin', MEMBER: 'a member' } as const

/** /invite?token=…: who invited you to which workspace, and a button to join it. */
export default function Invite() {
  const nav = useNavigate()
  const { user, signOut } = useAuth()
  const { reload } = useWorkspace()
  const token = new URLSearchParams(useLocation().search).get('token') ?? ''
  const { data: preview, isLoading, isError } = useQuery({
    queryKey: ['invite', token],
    queryFn: ({ signal }) => inviteApi.preview(token, { signal }),
    enabled: !!token,
    retry: false,
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const accept = async () => {
    setBusy(true)
    setError('')
    try {
      await inviteApi.accept(token)
      await reload()
      nav('/dashboard')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not accept the invite.')
      setBusy(false)
    }
  }

  const valid = !!token && !!preview?.valid
  const inviter = preview?.invitedByName || 'A teammate'
  const workspace = preview?.workspaceName || 'a workspace'

  return (
    <CenteredScreen
      busy={isLoading}
      footer={<>Signed in as <b>{user?.email || 'your account'}</b> · <button type="button" onClick={() => { signOut(); nav('/auth') }}>Not you?</button></>}
    >
      {isLoading ? (
        <div className="cs-loading"><Spinner /><span>Loading invite…</span></div>
      ) : valid ? (
        <>
          <div className="iv-faces" aria-hidden>
            <span className="iv-face">{inviter.charAt(0).toUpperCase()}</span>
            <span className="iv-plus"><AppIcon name="plus" /></span>
            <span className="iv-face iv-face--you">You</span>
          </div>
          <small className="iv-eyebrow">Workspace invitation</small>
          <h1>{inviter} invited you to <em>{workspace}</em></h1>
          <p>{preview?.role ? `You'll join as ${ROLE_TEXT[preview.role]} and can build on shared projects.` : 'Join the workspace to build together on shared projects.'}</p>
          {error && <NeuAlert>{error}</NeuAlert>}
          <NeuButton variant="primary" size="lg" block onClick={() => void accept()} disabled={busy}>
            {busy ? 'Joining…' : 'Accept & join'}<AppIcon name="arrowRight" />
          </NeuButton>
        </>
      ) : (
        <>
          <span className="cs-icon" aria-hidden><AppIcon name="warning" size={24} /></span>
          <h1>Invite unavailable</h1>
          <p>{!token ? 'This link is missing its invite code.' : isError ? 'We could not load this invite. Check the link and try again.' : preview?.reason || 'This invite link is invalid or has expired.'}</p>
          <NeuButton size="lg" block href="/dashboard">Go to dashboard<AppIcon name="arrowRight" /></NeuButton>
        </>
      )}
    </CenteredScreen>
  )
}
