import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useStartProject } from '../../../api/queries'
import { NeuAlert, NeuButton } from '../../../components/neu'
import { Spinner } from '../../../components/ui/States'
import { cx } from '../../../lib/cx'
import { useWorkspace } from '../../../workspace/WorkspaceContext'
import AppShell from '../../AppShell'
import PageHeader from '../../ui/PageHeader'
import { CATEGORIES, TEMPLATES, type Template, type TemplateCategory } from './content'
import TemplateCard from './TemplateCard'
import './templates.css'

/** Template gallery; picking one starts a real project from the template's prompt and opens it. */
export default function Templates() {
  const nav = useNavigate()
  const { selectedId } = useWorkspace()
  const start = useStartProject()
  const [category, setCategory] = useState<TemplateCategory>('all')
  const [error, setError] = useState('')
  const shown = category === 'all' ? TEMPLATES : TEMPLATES.filter((t) => t.category === category)

  const use = async (template: Template) => {
    if (start.isPending) return
    setError('')
    try {
      const project = await start.mutateAsync({ prompt: template.prompt, mode: 'BUILD', workspaceId: selectedId ?? undefined })
      nav(`/project/${project.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not start from this template.')
    }
  }

  return (
    <AppShell active="templates" surface="plain">
      <div className="page">
        <div className="page__inner">
          <PageHeader
            title={<>Start from something <em>beautiful</em></>}
            description="Pick a template, then ask Rendra to make it yours."
            action={start.isPending ? <Spinner label="Starting project" /> : undefined}
          />

          <div className="tp-cats" role="group" aria-label="Template categories">
            {CATEGORIES.map((c) => (
              <NeuButton key={c.id} size="sm" pill aria-pressed={category === c.id} className={cx('tp-cat', category === c.id && 'is-on')}
                onClick={() => setCategory(c.id)}>
                {c.label}
              </NeuButton>
            ))}
          </div>

          {error && <NeuAlert>{error}</NeuAlert>}

          <div className="tp-grid">
            {shown.map((t) => <TemplateCard key={t.name} template={t} busy={start.isPending} onUse={() => void use(t)} />)}
          </div>
        </div>
      </div>
    </AppShell>
  )
}
