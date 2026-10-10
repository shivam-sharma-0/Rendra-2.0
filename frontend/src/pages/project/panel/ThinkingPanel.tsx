import { AppIcon } from '../../../components/icons'
import { NeuButton } from '../../../components/neu'
import { Markdown } from '../chat/Markdown'

/** The full reasoning behind one reply ("Thought for Ns" in the chat). */
export function ThinkingPanel({ reasoning, seconds, onBack }: { reasoning: string; seconds: number; onBack: () => void }) {
  return (
    <div className="pv-page">
      <header className="pv-head">
        <NeuButton size="sm" onClick={onBack}><AppIcon name="back" />Back to details</NeuButton>
        <span className="pv-chip pv-chip--idle"><AppIcon name="clock" />Thought for {seconds}s</span>
      </header>
      <section className="pv-well pv-reasoning">
        {reasoning ? <Markdown content={reasoning} /> : <p className="pv-note">No reasoning was streamed for this response.</p>}
      </section>
    </div>
  )
}
