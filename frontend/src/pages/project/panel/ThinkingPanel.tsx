import { useState } from 'react'
import { Ico } from '../../../rendra/parts'
import { Markdown } from '../chat/Markdown'
import { BRAIN_ICON } from '../icons'

/* ── Thinking panel — full reasoning for a specific "Thought for Ns" click ─────────────────────── */
export function ThinkingPanel({ reasoning, seconds, onBack }: { reasoning: string; seconds: number; onBack: () => void }) {
  const [expanded, setExpanded] = useState(true)
  return (
    <div className="pw-thinking-panel">
      <div className="pw-thinking-panel-hd">
        <button type="button" className="pw-tp-back" onClick={onBack}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M19 12H5M12 5l-7 7 7 7" /></svg>
          Back to latest
        </button>
        <div className="pw-tp-meta">
          <span className="pw-tp-stat">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10" /><path d="M12 6v6l4 2" /></svg>
            {seconds}s
          </span>
        </div>
        <span className="pw-tp-title">Details</span>
        <div className="pw-tp-tabs">
          <button type="button" className="pw-tp-tab pw-tp-tab--on">Timeline</button>
          <button type="button" className="pw-tp-tab">Changes</button>
        </div>
      </div>
      <div className="pw-thinking-panel-body">
        <div className="pw-tp-card">
          <button type="button" className="pw-tp-card-hd" onClick={() => setExpanded(e => !e)}>
            <Ico d={BRAIN_ICON} size={14} />
            <span>Thought for {seconds}s</span>
            <svg className={`pw-tp-chevron${expanded ? ' pw-tp-chevron--up' : ''}`} width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
          </button>
          {expanded && (
            <div className="pw-tp-card-body">
              {reasoning
                ? <Markdown content={reasoning} />
                : <p className="pw-thinking-empty">No reasoning was streamed for this response.</p>}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
