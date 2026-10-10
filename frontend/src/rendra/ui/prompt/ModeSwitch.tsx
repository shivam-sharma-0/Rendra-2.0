import type { ProjectType } from '../../../api/projects'
import { NeuFoldingSwitch, type FoldingOption } from '../../../components/neu'
import { MODES } from './modes'

const OPTIONS: readonly FoldingOption<ProjectType>[] = MODES.map((m) => ({ value: m.id, label: m.label, icon: m.icon, hint: m.desc }))

/** Build / Plan / Chat switch of a prompt box: the selected mode shows its name, the others are icons; tooltips say what each does. */
export default function ModeSwitch({ mode, onChange, className }: { mode: ProjectType; onChange: (mode: ProjectType) => void; className?: string }) {
  return <NeuFoldingSwitch aria-label="Project mode" className={className} options={OPTIONS} value={mode} onChange={onChange} />
}
