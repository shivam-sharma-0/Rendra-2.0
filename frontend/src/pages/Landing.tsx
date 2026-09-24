import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { startProject, type ProjectType } from '../api/projects'
import { useAuth, type AuthUser } from '../auth/AuthContext'
import AuroraBackground from '../components/AuroraBackground'
import LoginModal from '../components/LoginModal'
import PromptBar from '../components/PromptBar'
import TopNav from '../components/TopNav'

const SUGGESTIONS = [
  'Clone a modern SaaS landing page',
  'Build a dashboard with auth',
  'REST API + Postgres backend',
  'Realtime chat app',
]

export default function Landing() {
  const { user, isAuthenticated } = useAuth()
  const navigate = useNavigate()

  const [modalOpen, setModalOpen] = useState(false)
  const [pendingPrompt, setPendingPrompt] = useState<{ prompt: string; mode: ProjectType } | null>(null)
  const [busy, setBusy] = useState(false)

  /** Create the project and jump into it; surfaces failures by staying put. */
  async function create(prompt: string, mode: ProjectType) {
    if (busy) return
    setBusy(true)
    try {
      const project = await startProject(prompt, mode)
      navigate(`/project/${project.id}`)
    } catch {
      setBusy(false)
    }
  }

  /** Gate every build action behind auth. Logged in → create; else → prompt to sign in. */
  function startBuild(prompt: string, mode: ProjectType) {
    if (isAuthenticated) {
      void create(prompt, mode)
    } else {
      setPendingPrompt({ prompt, mode })
      setModalOpen(true)
    }
  }

  /** The modal established the session; a pending prompt now becomes a project, else → dashboard. */
  function handleAuthenticated() {
    setModalOpen(false)
    const pending = pendingPrompt
    setPendingPrompt(null)
    if (pending) void create(pending.prompt, pending.mode)
    else navigate('/dashboard')
  }

  return (
    <div className="relative flex min-h-screen flex-col">
      <AuroraBackground />
      <TopNav onSignIn={() => setModalOpen(true)} />

      <main className="flex flex-1 items-center justify-center px-5 pb-24 pt-6">
        <div className="animate-fade-up w-full max-w-2xl text-center">
          <span className="inline-flex items-center gap-2 rounded-full border border-border bg-[color:var(--surface)] px-3.5 py-1.5 text-xs font-medium text-muted">
            <span className="animate-pulse-soft h-1.5 w-1.5 rounded-full" style={{ background: 'var(--spark)' }} />
            Autonomous coding agent · plans, edits, runs &amp; verifies
          </span>

          <h1 className="font-display mt-6 text-[2.75rem] font-bold leading-[1.04] text-text sm:text-6xl">
            {isAuthenticated ? (
              <>Welcome back,{' '}<span className="text-accent-gradient">{firstName(user)}</span></>
            ) : (
              <>What will you <span className="text-accent-gradient">build</span> today?</>
            )}
          </h1>

          <p className="mx-auto mt-5 max-w-xl text-[15px] leading-relaxed text-muted sm:text-base">
            Describe your idea in plain language. Rendra plans it, writes the code across
            files, runs it in a sandbox, fixes errors, and ships — end to end.
          </p>

          <div className="mx-auto mt-8 max-w-xl text-left">
            <PromptBar onSubmit={startBuild} onRequireLogin={() => setModalOpen(true)} />
          </div>

          <div className="mt-5 flex flex-wrap items-center justify-center gap-2">
            {SUGGESTIONS.map((s) => (
              <button
                key={s}
                onClick={() => startBuild(s, 'BUILD')}
                className="font-mono rounded-full border border-border bg-[color:var(--surface)] px-3.5 py-1.5 text-[12px] text-muted transition hover:-translate-y-px hover:border-[color:var(--accent)] hover:text-accent"
              >
                {s}
              </button>
            ))}
          </div>
        </div>
      </main>

      <LoginModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        onAuthenticated={handleAuthenticated}
        intent={pendingPrompt?.prompt}
      />
    </div>
  )
}

function firstName(user: AuthUser | null) {
  if (!user) return 'builder'
  return user.name?.split(' ')[0] ?? user.email.split('@')[0]
}
