import { useState } from 'react'
import type { ProjectType } from '../../../api/projects'
import { NeuButton } from '../../../components/neu'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import { MODES } from './content'
import MenuPopover, { MenuItem } from './MenuPopover'

/** Build / Plan / Chat selector. */
export default function ModeMenu({ mode, onChange }: { mode: ProjectType; onChange: (mode: ProjectType) => void }) {
  const [open, setOpen] = useState(false)
  const current = MODES.find((m) => m.id === mode) ?? MODES[0]

  return (
    <div className="db-anchor">
      <NeuButton size="sm" pill aria-expanded={open} aria-haspopup="menu" onClick={() => setOpen((v) => !v)}>
        <Ico d={current.icon} size={14} sw={1.9} />{current.label}<Ico d={ICON.chevronDown} size={12} sw={2} />
      </NeuButton>
      <MenuPopover open={open} onClose={() => setOpen(false)} label="Project mode" align="end">
        {MODES.map((m) => (
          <MenuItem key={m.id} icon={m.icon} title={m.label} hint={m.desc} selected={m.id === mode} onSelect={() => { onChange(m.id); setOpen(false) }} />
        ))}
      </MenuPopover>
    </div>
  )
}
