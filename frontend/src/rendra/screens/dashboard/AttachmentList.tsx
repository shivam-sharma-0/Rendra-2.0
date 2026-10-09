import { ICON } from '../../icons'
import { Ico } from '../../parts'

/** Files attached to the prompt, each removable. */
export default function AttachmentList({ files, onRemove }: { files: readonly File[]; onRemove: (index: number) => void }) {
  if (files.length === 0) return null
  return (
    <ul className="db-files" aria-label="Attached files">
      {files.map((file, i) => (
        <li key={`${file.name}-${i}`} className="db-file neu-well">
          <Ico d={ICON.file} size={13} sw={1.8} />
          <span title={file.name}>{file.name}</span>
          <button type="button" onClick={() => onRemove(i)} aria-label={`Remove ${file.name}`}><Ico d={ICON.close} size={11} sw={2} /></button>
        </li>
      ))}
    </ul>
  )
}
