import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { NeuIconButton } from '../../../components/neu'
import { ICON } from '../../icons'
import { Ico } from '../../parts'
import MenuPopover, { MenuItem } from './MenuPopover'

/** "+" button: upload files, import a project or use a skill. */
export default function AttachMenu({ onUpload }: { onUpload: () => void }) {
  const nav = useNavigate()
  const [open, setOpen] = useState(false)
  const pick = (action: () => void) => () => { setOpen(false); action() }

  return (
    <div className="db-anchor">
      <NeuIconButton size="md" aria-label="Add attachments" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        <Ico d={ICON.plus} size={16} sw={2} />
      </NeuIconButton>
      <MenuPopover open={open} onClose={() => setOpen(false)} label="Add to your prompt">
        <MenuItem icon={ICON.uploadTray} title="Upload a file" hint="Upload from your device" onSelect={pick(onUpload)} />
        <MenuItem icon={ICON.externalLink} title="Import an existing project" hint="Bring in code from GitHub or a folder" onSelect={pick(() => nav('/import'))} />
        <MenuItem icon={ICON.globe} title="Use a skill" hint="Invoke a saved skill or tool" onSelect={pick(() => nav('/skills'))} />
      </MenuPopover>
    </div>
  )
}
