import { useState } from 'react'
import type { ProjectType } from '../../../api/projects'
import { NeuButton } from '../../../components/neu'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import { PROMPT_CHIPS } from './content'
import FloatingCards from './FloatingCards'
import PromptBox from './PromptBox'
import { useStartProject } from './useStartProject'

/** Headline, prompt box and starter chips, filling the first screen. */
export default function Hero() {
  const { start, busy, isAuthenticated } = useStartProject()
  const [draft, setDraft] = useState('')
  const [mode, setMode] = useState<ProjectType>('BUILD')

  return (
    <section className="lp-hero">
      <FloatingCards />

      <NeuButton href="#how" size="sm" pill className="lp-badge lp-rise">
        <span className="lp-badge__tag">New</span>
        <span className="lp-badge__text">Plan mode — think it through before you build</span>
        <Ico d={ICON.arrowRight} size={14} sw={2.2} />
      </NeuButton>

      <h1 className="lp-hero__title lp-rise lp-d1">Describe it.<br /><em>Rendra</em> builds it.</h1>
      <p className="lp-hero__lead lp-rise lp-d2">
        Turn one sentence into a working full-stack app — interface, database, login and hosting — then shape it by simply talking to it.
      </p>

      <PromptBox value={draft} onChange={setDraft} mode={mode} onModeChange={setMode}
        showModes={isAuthenticated} busy={busy} onSubmit={() => void start(draft, mode)} />

      <div className="lp-chips lp-rise lp-d4">
        {PROMPT_CHIPS.map((c) => (
          <NeuButton key={c.label} size="sm" pill onClick={() => setDraft(`Build ${c.prompt}`)}>
            <Ico d={c.icon} size={14} sw={2} />{c.label}
          </NeuButton>
        ))}
      </div>
    </section>
  )
}
