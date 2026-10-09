import { ICON } from '../../icons'
import { Ico } from '../../parts'

/** Files attached to a prompt, each removable. Renders nothing when there are none. */
export default function AttachmentList({ files, onRemove }: { files: readonly File[]; onRemove: (index: number) => void }) {
  if (files.length === 0) return null
  return (
    <ul className="prompt-files" aria-label="Attached files">
      {files.map((file, i) => (
        <li key={`${file.name}-${i}`} className="prompt-file neu-well">
          <Ico d={ICON.file} size={13} sw={1.8} />
          <span title={file.name}>{file.name}</span>
          <button type="button" onClick={() => onRemove(i)} aria-label={`Remove ${file.name}`}><Ico d={ICON.close} size={11} sw={2} /></button>
        </li>
      ))}
    </ul>
  )
}
