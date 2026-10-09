import { Link } from 'react-router-dom'
import type { Project } from '../../../api/projects'
import { cx } from '../../../lib/cx'
import { paletteFor, relTime } from '../../parts'
import StatusBadge from '../../ui/StatusBadge'
import { kindOf } from './projectFilters'

const STAR = 'M12 2l3 6.5 7 .9-5 4.7 1.3 6.9L12 17.8 5.4 21l1.3-6.9-5-4.7 7-.9z'

type Props = { project: Project; starred: boolean; onToggleStar: () => void }

/** One project in the list. The whole row opens the project; the star is a separate button above it. */
export default function ProjectRow({ project, starred, onToggleStar }: Props) {
  return (
    <div className="pr-row">
      <span className="pr-row__name">
        <span className="pr-row__thumb" style={{ background: paletteFor(project.id).bg }} aria-hidden />
        <span className="pr-row__text">
          <Link to={`/project/${project.id}`} className="pr-row__link">{project.name}</Link>
          <small>{kindOf(project)}</small>
        </span>
      </span>
      <span><StatusBadge project={project} /></span>
      <span className="pr-row__time">{relTime(project.lastActivityAt)}</span>
      <button type="button" className={cx('pr-star', starred && 'is-on')} aria-pressed={starred}
        aria-label={`${starred ? 'Unstar' : 'Star'} ${project.name}`} onClick={onToggleStar}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill={starred ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2" strokeLinejoin="round" aria-hidden><path d={STAR} /></svg>
      </button>
    </div>
  )
}
