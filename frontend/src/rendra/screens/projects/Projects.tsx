import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useProjects } from '../../../api/queries'
import { NeuButton, NeuPanel } from '../../../components/neu'
import { EmptyState, ErrorState, Spinner } from '../../../components/ui/States'
import { useStoredState } from '../../../lib/useStoredState'
import { useWorkspace } from '../../../workspace/WorkspaceContext'
import AppShell from '../../AppShell'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import PageHeader from '../../ui/PageHeader'
import ProjectCard from '../../ui/ProjectCard'
import ProjectRow from './ProjectRow'
import ProjectsToolbar from './ProjectsToolbar'
import { filterProjects, tabCounts, type ProjectLayout, type ProjectSort, type ProjectTab, type StatusFilter } from './projectFilters'
import { useStarredProjects } from './useStarredProjects'
import './projects.css'

const EMPTY: Record<ProjectTab, { title: string; hint: string }> = {
  all: { title: 'No projects yet', hint: 'Start a new project from the dashboard.' },
  mine: { title: 'No projects yet', hint: 'Start a new project from the dashboard.' },
  shared: { title: 'Nothing shared with you yet', hint: 'Projects teammates share will appear here.' },
  starred: { title: 'No starred projects', hint: 'Star a project to pin it here.' },
}

/** All of the workspace's projects, with tabs, status filter, sort and a list or grid layout. */
export default function Projects() {
  const nav = useNavigate()
  const { selectedId } = useWorkspace()
  const { data: page, isLoading, isError, refetch } = useProjects(selectedId)
  const all = useMemo(() => page?.content ?? [], [page])

  const [tab, setTab] = useState<ProjectTab>('all')
  const [status, setStatus] = useState<StatusFilter>('any')
  const [sort, setSort] = useState<ProjectSort>('recent')
  const [layout, setLayout] = useStoredState<ProjectLayout>('rendra_projects_layout', (raw) => (raw === 'grid' ? 'grid' : 'list'), String)
  const { starred, toggle } = useStarredProjects()

  const shown = useMemo(() => filterProjects(all, { tab, status, sort, starred }), [all, tab, status, sort, starred])

  return (
    <AppShell active="projects" surface="plain">
      <div className="page">
        <div className="page__inner">
          <PageHeader
            title="Projects"
            description="Everything your workspace is building — yours and your team's."
            action={<NeuButton variant="primary" size="sm" pill onClick={() => nav('/dashboard')}><Ico d={ICON.plus} size={14} sw={2.4} />New project</NeuButton>}
          />
          <ProjectsToolbar tab={tab} counts={tabCounts(all, starred)} onTabChange={setTab} status={status} onStatusChange={setStatus}
            sort={sort} onSortChange={setSort} layout={layout} onLayoutChange={setLayout} />

          {isLoading ? (
            <div className="page__state"><Spinner /></div>
          ) : isError ? (
            <ErrorState message="We couldn't load projects." onRetry={() => void refetch()} />
          ) : shown.length === 0 ? (
            <EmptyState title={EMPTY[tab].title} hint={EMPTY[tab].hint} />
          ) : layout === 'list' ? (
            <NeuPanel size="sm" className="pr-list" role="list">
              <div className="pr-row pr-row--head" aria-hidden><span>Name</span><span>Status</span><span>Last edited</span><span /></div>
              {shown.map((p) => (
                <div role="listitem" key={p.id}><ProjectRow project={p} starred={starred.has(p.id)} onToggleStar={() => toggle(p.id)} /></div>
              ))}
            </NeuPanel>
          ) : (
            <div className="pr-grid">{shown.map((p) => <ProjectCard key={p.id} project={p} />)}</div>
          )}
        </div>
      </div>
    </AppShell>
  )
}
