import { useState } from 'react'
import type { SuggestedAction } from '../../../api/projects'

export function BuildSuggestion({ action, onBuild }: {
  action: SuggestedAction
  onBuild?: (action: SuggestedAction) => Promise<void>
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()

  const build = async () => {
    if (busy || !onBuild) return
    setBusy(true)
    setError(undefined)
    try {
      await onBuild(action)
    } catch {
      setError('Could not start the build. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  return <div className="msg-suggestion">
    <button type="button" className="msg-suggestion__build" disabled={busy || !onBuild} onClick={() => void build()}>
      {busy ? 'Starting…' : action.label}
    </button>
    {error && <p role="alert">{error}</p>}
  </div>
}
