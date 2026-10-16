import { type ChangeEvent, type FormEvent, useEffect, useRef, useState } from 'react'
import { type Project, type ProjectType } from '../../../api/projects'
import { NeuIconButton } from '../../../components/neu'
import { ICON } from '../../../rendra/icons'
import { Ico } from '../../../rendra/parts'
import { AttachMenu, AttachmentList, ModeSwitch } from '../../../rendra/ui/prompt'
import { CLOSE_ICON, REPLY_ICON } from '../icons'
import type { Quote } from '../workspaceTypes'

/** Import and skills are not built yet; their menu items just close the menu, as before. */
const notYet = () => {}
const STOP_ICON = 'M7 7h10v10H7z'

type ComposerProps = {
  project: Pick<Project, 'id' | 'type'> | null
  onSend: (prompt: string, mode: ProjectType, files: File[]) => void
  quote: Quote | null; onClearQuote: () => void
  editRequest: { text: string; nonce: number } | null
  /** While a run is active the send button becomes Stop. */
  running?: boolean; stopping?: boolean; onStop?: () => void
}

const modeKey = (projectId: string) => `rendra:project-mode:${projectId}`

function rememberedMode(projectId: string, fallback: ProjectType): ProjectType {
  try {
    const saved = localStorage.getItem(modeKey(projectId))
    if (saved === 'BUILD' || saved === 'PLAN' || saved === 'CHAT') return saved
  } catch { /* Storage may be blocked; the loaded project's mode remains usable. */ }
  return fallback
}

/** Mount only after loading the project; project changes reset drafts and restore that project's mode. */
export function Composer({ project, ...props }: ComposerProps) {
  return project ? <LoadedComposer key={project.id} project={project} {...props} /> : null
}

/** Enter sends, Shift+Enter adds a line. */
function LoadedComposer({ project, onSend, quote, onClearQuote, editRequest, running = false, stopping = false, onStop }: Omit<ComposerProps, 'project'> & {
  project: Pick<Project, 'id' | 'type'>
}) {
  const [draft, setDraft] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [mode, setMode] = useState(() => rememberedMode(project.id, project.type))
  const fileInputRef = useRef<HTMLInputElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const chooseMode = (next: ProjectType) => {
    setMode(next)
    try { localStorage.setItem(modeKey(project.id), next) } catch { /* Keep the in-memory selection. */ }
  }

  useEffect(() => { if (quote) textareaRef.current?.focus() }, [quote])

  // "Edit" on a past user message loads its text back into the composer to tweak and resend as a new
  // turn — it does not mutate or delete the original message. Keyed on `editRequest`'s object identity
  // (a nonce), not its text, so re-editing the same message twice in a row still re-triggers this.
  useEffect(() => {
    if (!editRequest) return
    setDraft(editRequest.text)
    textareaRef.current?.focus()
  }, [editRequest])

  const addFiles = (e: ChangeEvent<HTMLInputElement>) => {
    const picked = e.target.files
    if (picked) setFiles((previous) => [...previous, ...Array.from(picked)])
    e.target.value = ''
  }

  const submit = (e?: FormEvent) => {
    e?.preventDefault()
    const text = draft.trim()
    if (!text) return
    const prompt = quote ? `> ${quote.text.replace(/\n/g, '\n> ')}\n\n${text}` : text
    onSend(prompt, mode, files)
    setDraft('')
    setFiles([])
    onClearQuote()
  }

  return (
    <form className="ws-composer" onSubmit={submit}>
      {quote && (
        <div className="ws-quote">
          <Ico d={REPLY_ICON} size={13} />
          <span>{quote.text}</span>
          <button type="button" onClick={onClearQuote} aria-label="Remove quoted reference">
            <Ico d={CLOSE_ICON} size={11} />
          </button>
        </div>
      )}
      <div className="ws-composer__field neu-well neu-well--deep">
        <textarea
          ref={textareaRef}
          rows={2}
          value={draft}
          aria-label="Message Rendra"
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) submit(e) }}
          placeholder={quote ? 'Ask about the selected part…' : 'Ask Rendra to make a change…'}
        />
      </div>
      <AttachmentList files={files} onRemove={(index) => setFiles((previous) => previous.filter((_, i) => i !== index))} />
      <div className="ws-composer__bar">
        <input type="file" multiple hidden ref={fileInputRef} onChange={addFiles} />
        <AttachMenu size="sm" side="above" onUpload={() => fileInputRef.current?.click()} onImport={notYet} onUseSkill={notYet} />
        <ModeSwitch mode={mode} onChange={chooseMode} />
        <span className="ws-grow" />
        {running && onStop ? (
          <NeuIconButton type="button" variant="accent" className="ws-composer__send" aria-label="Stop" title="Stop"
            onClick={onStop} disabled={stopping}>
            <Ico d={STOP_ICON} size={14} sw={2.4} />
          </NeuIconButton>
        ) : (
          <NeuIconButton type="submit" variant="accent" className="ws-composer__send" aria-label="Send" disabled={!draft.trim()}>
            <Ico d={ICON.arrowUp} size={16} sw={2.4} />
          </NeuIconButton>
        )}
      </div>
    </form>
  )
}
