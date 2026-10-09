import type { CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import type { Project } from '../../../api/projects'
import { relTime, statusOf, type paletteFor } from '../../parts'

type Palette = ReturnType<typeof paletteFor>

/** A recent project: a miniature preview in its palette, its status, name and last edit. */
export default function ProjectCard({ project, palette }: { project: Project; palette: Palette }) {
  const status = statusOf(project)
  const colours = { '--card-bg': palette.bg, '--card-fg': palette.fg, '--card-tile': palette.card } as CSSProperties

  return (
    <Link to={`/project/${project.id}`} className="db-card neu-panel neu-panel--sm neu-panel--interactive">
      <div className="db-card__well neu-well neu-well--deep">
        <div className="db-card__preview" style={colours} aria-hidden>
          <span className="db-card__bar" />
          <span className="db-card__bar db-card__bar--short" />
          <div className="db-card__tiles"><span /><span /><span /></div>
        </div>
        <span className="db-card__status"><i style={{ background: status.dot }} />{status.label}</span>
      </div>
      <div className="db-card__meta">
        <b>{project.name}</b>
        <small>Edited {relTime(project.lastActivityAt)}</small>
      </div>
    </Link>
  )
}
