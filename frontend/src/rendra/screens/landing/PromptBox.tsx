import type { FormEvent, KeyboardEvent } from 'react'
import type { ProjectType } from '../../../api/projects'
import { NeuIconButton, NeuPanel, NeuSegmentedControl } from '../../../components/neu'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import { PROJECT_MODES, PROMPT_IDEAS } from './content'
import { useTypewriter } from '../../hooks/useTypewriter'

type Props = {
  value: string
  onChange: (value: string) => void
  mode: ProjectType
  onModeChange: (mode: ProjectType) => void
  /** Build / Plan / Chat is offered only to signed-in users. */
  showModes: boolean
  busy: boolean
  onSubmit: () => void
}

/** The hero's "describe your app" box. Enter submits; Shift+Enter adds a line. */
export default function PromptBox({ value, onChange, mode, onModeChange, showModes, busy, onSubmit }: Props) {
  const idea = useTypewriter(PROMPT_IDEAS)

  const submit = (e: FormEvent) => {
    e.preventDefault()
    onSubmit()
  }
  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      e.currentTarget.form?.requestSubmit()
    }
  }

  return (
    <NeuPanel as="section" className="lp-prompt lp-rise lp-d3" aria-label="Describe your app">
      <form onSubmit={submit}>
        <div className="lp-prompt__field neu-well neu-well--deep">
          <textarea rows={2} value={value} onChange={(e) => onChange(e.target.value)} onKeyDown={onKeyDown} aria-label="Describe your app" />
          {value.length === 0 && (
            <div className="lp-prompt__hint" aria-hidden>Build {idea}<span className="lp-caret" /></div>
          )}
        </div>
        <div className="lp-prompt__bar">
          <NeuIconButton size="md" aria-label="Attach files"><Ico d={ICON.plus} sw={2} /></NeuIconButton>
          {showModes && (
            <NeuSegmentedControl aria-label="Project mode" className="lp-prompt__modes" options={PROJECT_MODES} value={mode} onChange={onModeChange} />
          )}
          <span className="lp-grow" />
          <NeuIconButton size="md" aria-label="Voice input"><Ico d={ICON.mic} sw={2} /></NeuIconButton>
          <NeuIconButton type="submit" size="lg" variant="accent" aria-label="Start building" disabled={busy}>
            <Ico d={ICON.arrowUp} sw={2.4} />
          </NeuIconButton>
        </div>
      </form>
    </NeuPanel>
  )
}
