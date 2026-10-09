import { useState } from 'react'
import { NeuIconButton, NeuMenu, NeuMenuItem } from '../../../components/neu'
import { ICON } from '../../icons'
import { Ico } from '../../parts'

type Props = {
  onUpload: () => void
  onImport: () => void
  onUseSkill: () => void
  /** Button diameter: sm 32 · md 40. */
  size?: 'sm' | 'md'
  /** Open the menu above the button (for a composer at the bottom of the screen). */
  side?: 'below' | 'above'
}

/** "+" button of a prompt box: upload files, import a project or use a skill. */
export default function AttachMenu({ onUpload, onImport, onUseSkill, size = 'md', side = 'below' }: Props) {
  const [open, setOpen] = useState(false)
  const pick = (action: () => void) => () => { setOpen(false); action() }

  return (
    <div className="neu-menu-anchor">
      <NeuIconButton size={size} aria-label="Add attachments" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        <Ico d={ICON.plus} size={size === 'sm' ? 15 : 16} sw={2} />
      </NeuIconButton>
      <NeuMenu open={open} onClose={() => setOpen(false)} label="Add to your prompt" side={side} className="prompt-menu">
        <NeuMenuItem icon={ICON.uploadTray} title="Upload a file" hint="Upload from your device" onSelect={pick(onUpload)} />
        <NeuMenuItem icon={ICON.externalLink} title="Import an existing project" hint="Bring in code from GitHub or a folder" onSelect={pick(onImport)} />
        <NeuMenuItem icon={ICON.globe} title="Use a skill" hint="Invoke a saved skill or tool" onSelect={pick(onUseSkill)} />
      </NeuMenu>
    </div>
  )
}
