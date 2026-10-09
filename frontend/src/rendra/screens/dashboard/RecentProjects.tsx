import { Link } from 'react-router-dom'
import { useProjects } from '../../../api/queries'
import { neuButtonClass } from '../../../components/neu'
import { EmptyState, ErrorState, Spinner } from '../../../components/ui/States'
import { useWorkspace } from '../../../workspace/WorkspaceContext'
import { ICON } from '../../icons'
import { Ico, paletteFor } from '../../parts'
import ProjectCard from './ProjectCard'

const SHOWN = 3

/** "Jump back in": the workspace's most recent projects. */
export default function RecentProjects() {
  const { selectedId } = useWorkspace()
  // Same query key as the sidebar recents, so both share one request.
  const { data: page, isLoading, isError, refetch } = useProjects(selectedId)
  const projects = (page?.content ?? []).slice(0, SHOWN)

  return (
    <section className="db-recent" aria-labelledby="db-recent-title">
      <header className="db-recent__head">
        <h2 id="db-recent-title">Jump back in</h2>
        <Link to="/projects" className={neuButtonClass({ size: 'sm', pill: true })}>Browse all <Ico d={ICON.arrowRight} size={14} sw={2.2} /></Link>
      </header>

      {isLoading ? (
        <div className="db-recent__state"><Spinner /></div>
      ) : isError ? (
        <ErrorState message="We couldn't load your projects." onRetry={() => void refetch()} />
      ) : projects.length === 0 ? (
        <EmptyState title="No projects yet" hint="Describe an idea above to create your first one." />
      ) : (
        <div className="db-grid">
          {projects.map((p) => <ProjectCard key={p.id} project={p} palette={paletteFor(p.id)} />)}
        </div>
      )}
    </section>
  )
}
