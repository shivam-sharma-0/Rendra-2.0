import { Link } from 'react-router-dom'
import type { Project } from '../../api/projects'
import { paletteFor, relTime } from '../parts'
import MiniPreview from './MiniPreview'
import StatusBadge from './StatusBadge'
import './ui.css'

/** A project as a card: a miniature preview in its palette, its status, name and last edit. */
export default function ProjectCard({ project }: { project: Project }) {
  const palette = paletteFor(project.id)

  return (
    <Link to={`/project/${project.id}`} className="pcard neu-panel neu-panel--sm neu-panel--interactive">
      <div className="pcard__well neu-well neu-well--deep">
        <MiniPreview colours={palette} />
        <StatusBadge project={project} className="pcard__status" />
      </div>
      <div className="pcard__meta">
        <b>{project.name}</b>
        <small>Edited {relTime(project.lastActivityAt)}</small>
      </div>
    </Link>
  )
}
