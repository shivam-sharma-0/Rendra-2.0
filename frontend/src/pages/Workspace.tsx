import { Link, useLocation } from 'react-router-dom'
import AuroraBackground from '../components/AuroraBackground'
import Logo from '../components/Logo'

/** Placeholder destination after a build is started — the real Agent Workspace lands here next. */
export default function Workspace() {
  const location = useLocation()
  const prompt = (location.state as { prompt?: string } | null)?.prompt

  return (
    <div className="relative grid min-h-screen place-items-center px-5">
      <AuroraBackground />
      <div className="animate-fade-up card w-full max-w-lg rounded-3xl p-8 text-center">
        <Logo className="justify-center" />
        <h1 className="font-display mt-6 text-2xl font-bold text-text">Spinning up your workspace…</h1>
        <p className="mt-2 text-sm text-muted">
          The Agent Workspace (chat · plan · editor · terminal · preview) is coming next.
        </p>

        {prompt && (
          <div className="mt-6 rounded-2xl border border-border bg-[color:var(--surface)] p-4 text-left">
            <div className="font-mono mb-1.5 text-[11px] font-medium uppercase tracking-wider text-faint">Your prompt</div>
            <p className="font-mono text-[13px] leading-relaxed text-text">{prompt}</p>
          </div>
        )}

        <Link
          to="/"
          className="mt-7 inline-flex items-center gap-2 text-sm font-semibold text-accent hover:underline"
        >
          ← Back home
        </Link>
      </div>
    </div>
  )
}
