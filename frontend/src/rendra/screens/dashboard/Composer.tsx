import { useRef, useState, type ChangeEvent, type FormEvent, type KeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import type { ProjectType } from '../../../api/projects'
import { useStartProject } from '../../../api/queries'
import { NeuAlert, NeuButton, NeuIconButton, NeuPanel } from '../../../components/neu'
import { Spinner } from '../../../components/ui/States'
import { useWorkspace } from '../../../workspace/WorkspaceContext'
import { useTypewriter } from '../../hooks/useTypewriter'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import { AttachMenu, AttachmentList, ModeMenu } from '../../ui/prompt'
import { PLACEHOLDERS } from './content'

type Props = {
  draft: string
  onDraftChange: (value: string) => void
  mode: ProjectType
  onModeChange: (mode: ProjectType) => void
}

/** Prompt box that starts a project. Ctrl/⌘+Enter sends. */
export default function Composer({ draft, onDraftChange, mode, onModeChange }: Props) {
  const nav = useNavigate()
  const { selectedId } = useWorkspace()
  const start = useStartProject()
  const placeholder = useTypewriter(PLACEHOLDERS[mode])
  const fileInput = useRef<HTMLInputElement>(null)
  const [files, setFiles] = useState<File[]>([])
  const [error, setError] = useState('')
  const canSend = draft.trim().length > 0 && !start.isPending

  const addFiles = (e: ChangeEvent<HTMLInputElement>) => {
    const picked = e.target.files
    if (picked) setFiles((prev) => [...prev, ...Array.from(picked)])
    e.target.value = ''
  }

  const send = async (e?: FormEvent) => {
    e?.preventDefault()
    if (!canSend) return
    setError('')
    try {
      const project = await start.mutateAsync({ prompt: draft.trim(), mode, workspaceId: selectedId ?? undefined, files })
      nav(`/project/${project.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not start the project. Please try again.')
    }
  }

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault()
      e.currentTarget.form?.requestSubmit()
    }
  }

  return (
    <NeuPanel as="section" className="db-composer" aria-label="Start a project">
      <form onSubmit={(e) => void send(e)}>
        <div className="db-composer__field neu-well neu-well--deep">
          <textarea rows={2} value={draft} placeholder={placeholder} aria-label="Describe what to build"
            onChange={(e) => onDraftChange(e.target.value)} onKeyDown={onKeyDown} />
        </div>
        <AttachmentList files={files} onRemove={(i) => setFiles((prev) => prev.filter((_, j) => j !== i))} />
        {error && <NeuAlert className="db-composer__error">{error}</NeuAlert>}

        <div className="db-composer__bar">
          <input type="file" multiple hidden ref={fileInput} onChange={addFiles} />
          <AttachMenu onUpload={() => fileInput.current?.click()} onImport={() => nav('/import')} onUseSkill={() => nav('/skills')} />
          <NeuButton size="sm" pill className="db-composer__template" onClick={() => nav('/templates')}>
            <Ico d={ICON.templateAdd} size={14} sw={1.9} /><span>Start from a template</span>
          </NeuButton>
          <span className="db-grow" />
          <ModeMenu mode={mode} onChange={onModeChange} />
          <NeuIconButton type="submit" size="lg" variant="accent" aria-label="Start building" disabled={!canSend}>
            {start.isPending ? <Spinner size={16} label="Starting" /> : <Ico d={ICON.arrowUp} size={17} sw={2.4} />}
          </NeuIconButton>
        </div>
      </form>
    </NeuPanel>
  )
}
