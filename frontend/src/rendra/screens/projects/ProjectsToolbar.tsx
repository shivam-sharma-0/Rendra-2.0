import { useState } from 'react'
import { NeuButton, NeuMenu, NeuMenuItem, NeuSegmentedControl, type SegmentOption } from '../../../components/neu'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import { STATUS_OPTIONS, type ProjectLayout, type ProjectSort, type ProjectTab, type StatusFilter } from './projectFilters'

const TAB_LABELS: Record<ProjectTab, string> = { all: 'All', mine: 'Created by me', shared: 'Shared with me', starred: 'Starred' }

const LAYOUTS: readonly SegmentOption<ProjectLayout>[] = [
  { value: 'list', title: 'List view', label: <Ico d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01" size={15} sw={2} /> },
  { value: 'grid', title: 'Grid view', label: <Ico d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z" size={15} sw={2} /> },
]

type Props = {
  tab: ProjectTab
  counts: Record<ProjectTab, number>
  onTabChange: (tab: ProjectTab) => void
  status: StatusFilter
  onStatusChange: (status: StatusFilter) => void
  sort: ProjectSort
  onSortChange: (sort: ProjectSort) => void
  layout: ProjectLayout
  onLayoutChange: (layout: ProjectLayout) => void
}

/** Tabs, status filter, sort and list/grid switch above the project list. */
export default function ProjectsToolbar(props: Props) {
  const [statusOpen, setStatusOpen] = useState(false)
  const tabs = (Object.keys(TAB_LABELS) as ProjectTab[]).map((id) => ({
    value: id,
    label: <>{TAB_LABELS[id]} <span className="pr-count">{props.counts[id]}</span></>,
  }))

  return (
    <div className="pr-toolbar">
      <NeuSegmentedControl aria-label="Projects" className="pr-tabs" options={tabs} value={props.tab} onChange={props.onTabChange} />
      <span className="pr-grow" />
      <div className="neu-menu-anchor">
        <NeuButton size="sm" pill aria-haspopup="menu" aria-expanded={statusOpen} onClick={() => setStatusOpen((v) => !v)}>
          <Ico d="M4 6h16M7 12h10M10 18h4" size={14} sw={2} />{STATUS_OPTIONS.find((s) => s.id === props.status)?.label}
        </NeuButton>
        <NeuMenu open={statusOpen} onClose={() => setStatusOpen(false)} label="Filter by status" align="end">
          {STATUS_OPTIONS.map((s) => (
            <NeuMenuItem key={s.id} title={s.label} selected={s.id === props.status} onSelect={() => { props.onStatusChange(s.id); setStatusOpen(false) }} />
          ))}
        </NeuMenu>
      </div>
      <NeuButton size="sm" pill aria-label={`Sorted by ${props.sort === 'name' ? 'name' : 'last edited'}; change sort`}
        onClick={() => props.onSortChange(props.sort === 'name' ? 'recent' : 'name')}>
        {props.sort === 'name' ? 'Name' : 'Last edited'}<Ico d={ICON.chevronDown} size={12} sw={2.4} />
      </NeuButton>
      <NeuSegmentedControl aria-label="Layout" className="pr-layout" options={LAYOUTS} value={props.layout} onChange={props.onLayoutChange} />
    </div>
  )
}
