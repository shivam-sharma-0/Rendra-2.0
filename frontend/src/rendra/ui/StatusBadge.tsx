import type { Project } from '../../api/projects'
import { cx } from '../../lib/cx'
import { statusOf } from '../parts'
import './ui.css'

/** Deployment status pill (Live / Building / Failed / Draft). */
export default function StatusBadge({ project, className }: { project: Project; className?: string }) {
  const status = statusOf(project)
  return (
    <span className={cx('status-badge', className)} style={{ background: status.bg, color: status.ink }}>
      <i style={{ background: status.dot }} aria-hidden />{status.label}
    </span>
  )
}
