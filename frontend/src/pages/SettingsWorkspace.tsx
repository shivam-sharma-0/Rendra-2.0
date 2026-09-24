import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { memberApi, workspaceApi, type Member, type Workspace, type WorkspaceRole } from '../api/projects'
import { ApiClientError } from '../auth/api'
import AppShell from '../components/AppShell'

export default function SettingsWorkspace() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const wantId = params.get('id')

  const [ws, setWs] = useState<Workspace | null>(null)
  const [members, setMembers] = useState<Member[] | null>(null)
  const [name, setName] = useState('')
  const [invite, setInvite] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null)

  // Resolve the target workspace (by ?id, else personal) + its members.
  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const all = await workspaceApi.list()
        const chosen = (wantId && all.find((w) => w.id === wantId)) || all.find((w) => w.personal) || all[0]
        if (cancelled || !chosen) return
        setWs(chosen)
        setName(chosen.name)
        const m = await memberApi.list(chosen.id)
        if (!cancelled) setMembers(m)
      } catch {
        if (!cancelled) setNotice({ kind: 'err', text: 'Could not load workspace settings.' })
      }
    })()
    return () => {
      cancelled = true
    }
  }, [wantId])

  if (!ws) {
    return (
      <AppShell>
        <div className="mx-auto max-w-3xl px-8 py-10 text-[13px] text-faint">Loading settings…</div>
      </AppShell>
    )
  }

  const isOwner = ws.role === 'OWNER'
  const isPrivileged = ws.role === 'OWNER' || ws.role === 'ADMIN'

  const run = async (fn: () => Promise<void>, ok?: string) => {
    if (busy) return
    setBusy(true)
    setNotice(null)
    try {
      await fn()
      if (ok) setNotice({ kind: 'ok', text: ok })
    } catch (e) {
      setNotice({ kind: 'err', text: e instanceof ApiClientError ? e.message : 'Something went wrong.' })
    } finally {
      setBusy(false)
    }
  }

  const reloadMembers = async () => setMembers(await memberApi.list(ws.id))

  return (
    <AppShell>
      <div className="mx-auto max-w-3xl px-8 py-10">
        <h1 className="font-display text-2xl font-bold text-text">Workspace settings</h1>
        <p className="mt-1 text-sm text-muted">Workspaces let you collaborate on projects in real time.</p>

        {notice && (
          <p className={`mt-4 text-[13px] font-medium ${notice.kind === 'ok' ? 'text-accent' : 'text-danger'}`}>
            {notice.text}
          </p>
        )}

        {/* ── Profile ─────────────────────────────────────────── */}
        <Section title="Workspace profile" subtitle="Control how this workspace appears.">
          <Row label="Name" hint="Visible to members.">
            <div className="flex items-center gap-2">
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                disabled={!isPrivileged}
                maxLength={120}
                className="w-64 rounded-[10px] border border-border bg-[color:var(--surface)] px-3 py-2 text-sm text-text outline-none focus-visible:border-[color:var(--accent)] disabled:opacity-60"
              />
              {isPrivileged && (
                <button
                  onClick={() => run(async () => setWs(await workspaceApi.rename(ws.id, name.trim())), 'Name saved.')}
                  disabled={busy || !name.trim() || name.trim() === ws.name}
                  className="rounded-[10px] bg-[color:var(--accent)] px-3.5 py-2 text-[13px] font-semibold text-[color:var(--accent-contrast)] transition hover:bg-[color:var(--accent-active)] disabled:opacity-50"
                >
                  Save
                </button>
              )}
            </div>
          </Row>
          <Row label="Workspace ID" hint="Unique identifier.">
            <button
              onClick={() => navigator.clipboard?.writeText(ws.id)}
              className="font-mono inline-flex items-center gap-2 rounded-[10px] border border-border px-3 py-1.5 text-[12px] text-muted transition hover:text-text"
              title="Copy"
            >
              {ws.id}
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden><rect x="9" y="9" width="13" height="13" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" /></svg>
            </button>
          </Row>
        </Section>

        {/* ── Members ─────────────────────────────────────────── */}
        <Section title="Members" subtitle="People who can access this workspace's projects.">
          {isPrivileged && (
            <div className="mb-3 flex items-center gap-2">
              <input
                value={invite}
                onChange={(e) => setInvite(e.target.value)}
                type="email"
                placeholder="teammate@work.com"
                className="flex-1 rounded-[10px] border border-border bg-[color:var(--surface)] px-3 py-2 text-sm text-text outline-none focus-visible:border-[color:var(--accent)]"
              />
              <button
                onClick={() =>
                  run(async () => {
                    await memberApi.invite(ws.id, invite.trim().toLowerCase())
                    setInvite('')
                    await reloadMembers()
                  }, 'Member added.')
                }
                disabled={busy || !invite.trim()}
                className="rounded-[10px] bg-[color:var(--spark)] px-4 py-2 text-[13px] font-semibold text-[color:var(--spark-contrast)] transition hover:bg-[color:var(--spark-hover)] disabled:opacity-50"
              >
                Invite
              </button>
            </div>
          )}
          <div className="divide-y divide-border">
            {members === null ? (
              <div className="py-3 text-[13px] text-faint">Loading members…</div>
            ) : (
              members.map((m) => (
                <div key={m.id} className="flex items-center gap-3 py-3">
                  <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-[color:var(--border-strong)] text-[12px] font-bold text-text">
                    {(m.name?.[0] ?? m.email?.[0] ?? '?').toUpperCase()}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-medium text-text">{m.name ?? m.email ?? 'Unknown'}</div>
                    <div className="truncate text-[12px] text-faint">{m.email}</div>
                  </div>
                  <RoleBadge role={m.role} />
                  {isOwner && m.role !== 'OWNER' && (
                    <button
                      onClick={() => run(async () => { await memberApi.remove(ws.id, m.id); await reloadMembers() }, 'Member removed.')}
                      disabled={busy}
                      className="rounded-md px-2 py-1 text-[12px] font-medium text-danger transition hover:bg-[color:var(--danger)]/10"
                    >
                      Remove
                    </button>
                  )}
                </div>
              ))
            )}
          </div>
        </Section>

        {/* ── Access ──────────────────────────────────────────── */}
        <Section title="Workspace access" subtitle="Ownership and membership.">
          {isOwner && members && members.some((m) => m.role !== 'OWNER') && (
            <Row label="Transfer ownership" hint="Make another member the owner.">
              <TransferControl
                members={members.filter((m) => m.role !== 'OWNER')}
                disabled={busy}
                onTransfer={(memberId) =>
                  run(async () => { await workspaceApi.transfer(ws.id, memberId); await reloadMembers(); setWs(await workspaceApi.get(ws.id)) }, 'Ownership transferred.')
                }
              />
            </Row>
          )}
          {!isOwner && (
            <Row label="Leave workspace" hint="You'll lose access to its projects.">
              <button
                onClick={() => run(async () => { await workspaceApi.leave(ws.id); navigate('/dashboard') })}
                disabled={busy}
                className="rounded-[10px] border border-border px-3.5 py-2 text-[13px] font-semibold text-text transition hover:bg-black/[0.03] dark:hover:bg-white/[0.05]"
              >
                Leave
              </button>
            </Row>
          )}
        </Section>

        {/* ── Danger zone ─────────────────────────────────────── */}
        {isOwner && !ws.personal && (
          <Section title="Danger zone" subtitle="">
            <Row label="Delete workspace" hint="Members lose access immediately.">
              <button
                onClick={() =>
                  run(async () => { await workspaceApi.remove(ws.id); navigate('/dashboard') })
                }
                disabled={busy}
                className="rounded-[10px] border border-[color:var(--danger)] px-3.5 py-2 text-[13px] font-semibold text-danger transition hover:bg-[color:var(--danger)]/10"
              >
                Delete
              </button>
            </Row>
          </Section>
        )}
      </div>
    </AppShell>
  )
}

function Section({ title, subtitle, children }: { title: string; subtitle: string; children: React.ReactNode }) {
  return (
    <section className="mt-8">
      <h2 className="text-sm font-semibold text-text">{title}</h2>
      {subtitle && <p className="mt-0.5 text-[12px] text-muted">{subtitle}</p>}
      <div className="card mt-3 rounded-2xl p-4">{children}</div>
    </section>
  )
}

function Row({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 py-2">
      <div className="min-w-0">
        <div className="text-[13px] font-medium text-text">{label}</div>
        {hint && <div className="text-[12px] text-faint">{hint}</div>}
      </div>
      {children}
    </div>
  )
}

function RoleBadge({ role }: { role: WorkspaceRole }) {
  return (
    <span className="rounded-full border border-border px-2 py-0.5 text-[11px] font-medium capitalize text-muted">
      {role.toLowerCase()}
    </span>
  )
}

function TransferControl({ members, disabled, onTransfer }: { members: Member[]; disabled: boolean; onTransfer: (memberId: string) => void }) {
  const [sel, setSel] = useState('')
  return (
    <div className="flex items-center gap-2">
      <select
        value={sel}
        onChange={(e) => setSel(e.target.value)}
        className="rounded-[10px] border border-border bg-[color:var(--surface)] px-2 py-2 text-[13px] text-text outline-none"
      >
        <option value="">Choose member…</option>
        {members.map((m) => (
          <option key={m.id} value={m.id}>{m.name ?? m.email}</option>
        ))}
      </select>
      <button
        onClick={() => sel && onTransfer(sel)}
        disabled={disabled || !sel}
        className="rounded-[10px] border border-border px-3.5 py-2 text-[13px] font-semibold text-text transition hover:bg-black/[0.03] disabled:opacity-50 dark:hover:bg-white/[0.05]"
      >
        Transfer
      </button>
    </div>
  )
}
