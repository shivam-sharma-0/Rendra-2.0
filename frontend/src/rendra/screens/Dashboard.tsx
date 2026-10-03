import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { useWorkspace } from '../../workspace/WorkspaceContext'
import { useProjects, useStartProject } from '../../api/queries'
import { projectApi, type ProjectType } from '../../api/projects'
import { EmptyState, ErrorState, Spinner } from '../../components/ui/States'
import AppShell from '../AppShell'
import { Ico, relTime, statusOf, CARD_PALETTE } from '../parts'

/**
 * Dashboard home — Figma "08 · Dashboard home". Greeting + prompt composer (Build/Plan/Chat) that
 * starts a real project, quick idea chips, and a "Jump back in" grid of recent projects (React Query,
 * with loading/empty/error states). Sidebar/top bar come from the shared AppShell.
 */

const MODES: { id: ProjectType; label: string; desc: string; icon: string }[] = [
  { id: 'BUILD', label: 'Build', desc: 'Writes and runs code for your app', icon: 'M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.4-.6-.6-2.4z' },
  { id: 'PLAN', label: 'Plan', desc: 'Maps pages, data and steps before building', icon: 'M9 5h11M9 12h11M9 19h11M4 5h.01M4 12h.01M4 19h.01' },
  { id: 'CHAT', label: 'Chat', desc: 'Ask questions without changing code', icon: 'M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z' },
]

const IDEAS = [
  { label: 'Client portal', icon: 'M16 21v-2a4 4 0 0 0-8 0v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', text: 'Build a client portal with invoices and file sharing' },
  { label: 'AI chatbot', icon: 'M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6', text: 'Build an AI support chatbot trained on my FAQ' },
  { label: 'Landing page', icon: 'M4 5h16v14H4zM4 9h16', text: 'Build a landing page for my mobile app launch' },
  { label: 'Dashboard', icon: 'M4 20V10M10 20V4M16 20v-7M22 20H2', text: 'Build a sales dashboard with charts and filters' },
]

function greeting(): string {
  const h = new Date().getHours()
  return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'
}

const PLACEHOLDERS: Record<ProjectType, string[]> = {
  BUILD: [
    'Build a landing page for my mobile app...',
    'Build a real-time chat application...',
    'Build a dashboard with analytics charts...',
    'Build a to-do list with drag and drop...',
  ],
  PLAN: [
    'Plan the architecture for a SaaS platform...',
    'Draft a data model for an e-commerce store...',
    'Map out the user journey for a booking app...',
  ],
  CHAT: [
    'Ask Rendra anything about your projects...',
    'How do I deploy this application?',
    'What is the best way to handle authentication?',
  ]
}

function useTypewriter(strings: string[], pauseMs = 2500) {
  const [text, setText] = useState('')
  const [index, setIndex] = useState(0)
  const [deleting, setDeleting] = useState(false)

  useEffect(() => {
    setText('')
    setIndex(0)
    setDeleting(false)
  }, [strings])

  useEffect(() => {
    const current = strings[index]
    let timeout: ReturnType<typeof setTimeout>

    if (deleting) {
      if (text.length === 0) {
        setDeleting(false)
        setIndex((i) => (i + 1) % strings.length)
        timeout = setTimeout(() => {}, 200)
      } else {
        timeout = setTimeout(() => setText(text.slice(0, -1)), 25)
      }
    } else {
      if (text.length === current.length) {
        timeout = setTimeout(() => setDeleting(true), pauseMs)
      } else {
        timeout = setTimeout(() => setText(current.slice(0, text.length + 1)), 40)
      }
    }

    return () => clearTimeout(timeout)
  }, [text, deleting, index, strings, pauseMs])

  return text
}

export default function Dashboard() {
  const nav = useNavigate()
  const { user } = useAuth()
  const { selectedId } = useWorkspace()
  const start = useStartProject()
  const { data: page, isLoading, isError, refetch } = useProjects(selectedId)

  const [draft, setDraft] = useState('')
  const [mode, setMode] = useState<ProjectType>('BUILD')
  const [modeOpen, setModeOpen] = useState(false)
  const [error, setError] = useState('')
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [files, setFiles] = useState<File[]>([])
  const [attachOpen, setAttachOpen] = useState(false)

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files) {
      setFiles((prev) => [...prev, ...Array.from(e.target.files!)])
    }
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  const removeFile = (index: number) => {
    setFiles((prev) => prev.filter((_, i) => i !== index))
  }

  const firstName = user?.name?.split(' ')[0] || user?.email?.split('@')[0] || 'there'
  const canSend = draft.trim().length > 0 && !start.isPending
  const currentMode = MODES.find((m) => m.id === mode)!
  const animatedPlaceholder = useTypewriter(PLACEHOLDERS[mode])

  const send = async () => {
    if (!draft.trim() || start.isPending) return
    setError('')
    try {
      const project = await start.mutateAsync({ prompt: draft.trim(), mode, workspaceId: selectedId ?? undefined })
      if (files.length) await projectApi.uploadAttachments(project.id, files)
      nav(`/project/${project.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not start the project. Please try again.')
    }
  }

  const projects = (page?.content ?? []).slice(0, 3)

  return (
    <AppShell active="home">
      <div className="kap-scroll">
        <section className="kap-hero">
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10 }}>
            <span className="kap-greet">{greeting()}, {firstName}</span>
            <h1>What shall we <em>build</em> today?</h1>
          </div>

          <div className="kap-composer">
            <textarea rows={2} value={draft} onChange={(e) => setDraft(e.target.value)} placeholder={animatedPlaceholder}
              onKeyDown={(e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); void send() } }}
              aria-label="Describe what to build" />
            
            {files.length > 0 && (
              <div className="kap-attachments">
                {files.map((file, i) => (
                  <div key={i} className="kap-attachment">
                    <Ico d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" size={14} />
                    <span>{file.name}</span>
                    <button type="button" onClick={() => removeFile(i)} aria-label="Remove file">
                      <Ico d="M18 6L6 18M6 6l12 12" size={12} />
                    </button>
                  </div>
                ))}
              </div>
            )}

            {error && <p style={{ margin: 0, color: '#C63B2B', fontSize: 13 }} role="alert">{error}</p>}
            <div className="kap-composer-row">
              <input type="file" multiple ref={fileInputRef} style={{ display: 'none' }} onChange={handleFileChange} />
              <div style={{ position: 'relative' }}>
                <button type="button" className="kap-cbtn" aria-label="Add attachments" onClick={() => setAttachOpen((v) => !v)}>
                  <Ico d="M12 5v14M5 12h14" size={15} />
                </button>
                {attachOpen && (
                  <>
                    <div className="kap-scrim" onClick={() => setAttachOpen(false)} />
                    <div className="kap-pop kap-attach-pop">
                      <button type="button" onClick={() => { setAttachOpen(false); fileInputRef.current?.click() }}>
                        <Ico d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12" size={15} />
                        <span><b>Upload a file</b><small>Upload from your device</small></span>
                      </button>
                      <button type="button" onClick={() => { setAttachOpen(false); nav('/import') }}>
                        <Ico d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3" size={15} />
                        <span><b>Import an existing project</b><small>Bring in code from GitHub or a folder</small></span>
                      </button>
                      <button type="button" onClick={() => { setAttachOpen(false); nav('/skills') }}>
                        <Ico d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" size={15} />
                        <span><b>Use a skill</b><small>Invoke a saved skill or tool</small></span>
                      </button>
                    </div>
                  </>
                )}
              </div>
              <button type="button" className="kap-ctpl" onClick={() => nav('/templates')}>
                <Ico d="M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM17.5 14v7M14 17.5h7" size={14} /> Start from a template
              </button>
              <div style={{ flexGrow: 1 }} />
              <div style={{ position: 'relative' }}>
                <button type="button" className="kap-cmode" aria-expanded={modeOpen} onClick={() => setModeOpen((v) => !v)}>
                  <Ico d={currentMode.icon} size={14} /> {currentMode.label}
                  <Ico d="m6 9 6 6 6-6" size={12} />
                </button>
                {modeOpen && (
                  <>
                    <div className="kap-scrim" onClick={() => setModeOpen(false)} />
                    <div className="kap-pop kap-mode-pop">
                      {MODES.map((m) => (
                        <button key={m.id} type="button" className={m.id === mode ? 'on' : ''} onClick={() => { setMode(m.id); setModeOpen(false) }}>
                          <Ico d={m.icon} size={14} />
                          <span style={{ flexGrow: 1 }}><b>{m.label}</b><small>{m.desc}</small></span>
                        </button>
                      ))}
                    </div>
                  </>
                )}
              </div>
              <button type="button" className="kap-csend" disabled={!canSend} onClick={() => void send()} aria-label="Start building">
                {start.isPending ? <Spinner size={16} /> : <Ico d="M12 19V5M5 12l7-7 7 7" size={16} />}
              </button>
            </div>
          </div>

          <div className="kap-ideas">
            {IDEAS.map((c) => (
              <button key={c.label} type="button" className="kap-idea" onClick={() => setDraft(c.text)}>
                <Ico d={c.icon} size={14} /> {c.label}
              </button>
            ))}
          </div>
        </section>

        <section className="kap-jump">
          <div className="kap-jump-bar">
            <div className="kap-jump-search">
              <Ico d="M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-3.5-3.5" size={14} /> Search
            </div>
            <div className="kap-jump-tabs">
              <span className="active">My projects</span>
              <span>Recently viewed</span>
              <span>Rendra templates</span>
            </div>
            <button type="button" className="kap-jump-all" onClick={(e) => { e.stopPropagation(); nav('/projects') }}>
              Browse all <Ico d="M5 12h14M13 6l6 6-6 6" size={14} />
            </button>
          </div>
          <div className="kap-jump-content">

          {isLoading ? (
            <div style={{ display: 'grid', placeItems: 'center', padding: 40 }}><Spinner /></div>
          ) : isError ? (
            <ErrorState message="We couldn't load your projects." onRetry={() => void refetch()} />
          ) : projects.length === 0 ? (
            <EmptyState title="No projects yet" hint="Describe an idea above to create your first one." />
          ) : (
            <div className="kap-pgrid">
              {projects.map((p, i) => {
                const c = CARD_PALETTE[i % CARD_PALETTE.length]
                const st = statusOf(p)
                return (
                  <button key={p.id} type="button" className="kap-pcard" onClick={() => nav(`/project/${p.id}`)}>
                    <div className="kap-pthumb" style={{ background: c.bg }}>
                      <span className="b1" style={{ background: c.fg }} />
                      <span className="b2" style={{ background: c.fg }} />
                      <div className="cards"><span style={{ background: c.card }} /><span style={{ background: c.card }} /><span style={{ background: c.card }} /></div>
                      <span className="kap-pstatus"><i style={{ background: st.dot }} />{st.label}</span>
                    </div>
                    <div className="kap-pmeta">
                      <div style={{ flexGrow: 1, minWidth: 0, textAlign: 'left' }}>
                        <b>{p.name}</b>
                        <small>Edited {relTime(p.lastActivityAt)}</small>
                      </div>
                    </div>
                  </button>
                )
              })}
            </div>
          )}
          </div>
        </section>
      </div>
    </AppShell>
  )
}
