import { useEffect, useState } from 'react'
import { agentApi, type AgentRun, type PlanReview } from '../../../api/projects'
import { NeuAlert, NeuButton, NeuDialog } from '../../../components/neu'

/** Review is fetched only on demand. Approval always binds the exact revision displayed. */
export function PlanApproval({ run }: { run: AgentRun }) {
  const [open, setOpen] = useState(false)
  const [plan, setPlan] = useState<PlanReview | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    setPlan(null); setError(null); setBusy(true)
    void agentApi.plan(run.projectId, run.id, { signal: controller.signal })
      .then((result) => { if (!controller.signal.aborted) setPlan(result) })
      .catch(() => { if (!controller.signal.aborted) setError('Unable to load this plan. Please try again.') })
      .finally(() => { if (!controller.signal.aborted) setBusy(false) })
    return () => controller.abort()
  }, [open, run.projectId, run.id])
  const approve = async () => {
    if (!plan || plan.state !== 'DRAFT' || busy) return
    setBusy(true); setError(null)
    try { setPlan(await agentApi.approvePlan(run.projectId, run.id, plan)) }
    catch { setError('Approval could not be saved. Reopen this review to check the current plan and your edit access.') }
    finally { setBusy(false) }
  }
  return <>
    <button type="button" className="msg-chip" onClick={() => setOpen(true)}>Review plan</button>
    {open && <NeuDialog title="Review build plan" onClose={() => setOpen(false)} size="lg"
      footer={<><NeuButton onClick={() => setOpen(false)}>Close</NeuButton>
        <NeuButton variant="primary" disabled={busy || plan?.state !== 'DRAFT'} onClick={() => void approve()}>
          {plan?.state === 'APPROVED' ? 'Plan approved' : 'Approve plan'}
        </NeuButton></>}>
      {busy && !plan && <p role="status">Loading plan...</p>}
      {error && <NeuAlert>{error}</NeuAlert>}
      {!busy && !error && !plan && <p>This reply has no reusable build plan.</p>}
      {plan && <>
        <p>Storage: {plan.storage}. Accounts: {plan.auth === 'ACCOUNTS' ? 'included' : 'not required'}.</p>
        {plan.state === 'INVALIDATED' && <NeuAlert>Code or requirements changed. Ask for an updated plan before building.</NeuAlert>}
        {plan.state === 'APPROVED' && <p role="status">Plan approved. Close this review and choose Build this on the reply to start.</p>}
        <ol>{plan.units.map((unit) => <li key={unit.id}><b>{unit.objective}</b>
          <p>Files: {unit.ownedPaths.join(', ')}</p></li>)}</ol>
      </>}
    </NeuDialog>}
  </>
}
