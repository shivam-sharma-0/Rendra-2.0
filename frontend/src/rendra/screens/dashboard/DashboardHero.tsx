import { useState } from 'react'
import type { ProjectType } from '../../../api/projects'
import { useAuth } from '../../../auth/AuthContext'
import { NeuButton } from '../../../components/neu'
import { Ico } from '../../parts'
import Composer from './Composer'
import { IDEAS, greetingFor } from './content'

/** Greeting, headline, the composer and idea chips. */
export default function DashboardHero() {
  const { user } = useAuth()
  const [draft, setDraft] = useState('')
  const [mode, setMode] = useState<ProjectType>('BUILD')
  const firstName = user?.name?.split(' ')[0] || user?.email?.split('@')[0] || 'there'

  return (
    <section className="db-hero">
      <div className="db-hero__intro">
        <span className="db-greet">{greetingFor(new Date())}, {firstName}</span>
        <h1 className="db-title">What shall we <em>build</em> today?</h1>
      </div>

      <Composer draft={draft} onDraftChange={setDraft} mode={mode} onModeChange={setMode} />

      <div className="db-ideas">
        {IDEAS.map((idea) => (
          <NeuButton key={idea.label} size="sm" pill onClick={() => setDraft(idea.text)}>
            <Ico d={idea.icon} size={14} sw={1.9} />{idea.label}
          </NeuButton>
        ))}
      </div>
    </section>
  )
}
