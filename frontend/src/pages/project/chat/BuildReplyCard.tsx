import type { AgentRun } from '../../../api/projects'
import { buildReply } from './buildReply'

/** The normal build surface; file activity remains available through Details. */
export function BuildReplyCard({ run, currentPreview = true, onOpenDetails, onOpenPreview }: { run: AgentRun; currentPreview?: boolean; onOpenDetails: () => void; onOpenPreview: () => void }) {
  const outcome = buildReply(run)
  return <section className="msg-build-card" aria-label="Build result" data-run-id={run.id}>
    <b>{outcome.title}</b>
    <p role="status">{outcome.preview && !currentPreview ? 'This is an earlier saved build. Open Details to inspect it.' : outcome.text}</p>
    <div className="msg-build-card__actions">
      <button type="button" onClick={onOpenDetails}>Details</button>
      <button type="button" onClick={onOpenPreview} disabled={!outcome.preview || !currentPreview} title={outcome.preview && currentPreview ? 'Open current saved preview' : 'A ready current saved preview is required'}>Preview</button>
    </div>
  </section>
}
