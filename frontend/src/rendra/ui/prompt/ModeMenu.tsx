import { useState } from 'react'
import type { ProjectType } from '../../../api/projects'
import { NeuButton, NeuMenu, NeuMenuItem } from '../../../components/neu'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import { MODES } from './modes'

type Props = {
  mode: ProjectType
  onChange: (mode: ProjectType) => void
  /** Which edge of the button the menu lines up with. */
  align?: 'start' | 'end'
  /** Open the menu above the button (for a composer at the bottom of the screen). */
  side?: 'below' | 'above'
}

/** Build / Plan / Chat selector of a prompt box. */
export default function ModeMenu({ mode, onChange, align = 'end', side = 'below' }: Props) {
  const [open, setOpen] = useState(false)
  const current = MODES.find((m) => m.id === mode) ?? MODES[0]

  return (
    <div className="neu-menu-anchor">
      <NeuButton size="sm" pill aria-expanded={open} aria-haspopup="menu" onClick={() => setOpen((v) => !v)}>
        <Ico d={current.icon} size={14} sw={1.9} />{current.label}<Ico d={ICON.chevronDown} size={12} sw={2} />
      </NeuButton>
      <NeuMenu open={open} onClose={() => setOpen(false)} label="Project mode" align={align} side={side} className="prompt-menu">
        {MODES.map((m) => (
          <NeuMenuItem key={m.id} icon={m.icon} title={m.label} hint={m.desc} selected={m.id === mode} onSelect={() => { onChange(m.id); setOpen(false) }} />
        ))}
      </NeuMenu>
    </div>
  )
}
