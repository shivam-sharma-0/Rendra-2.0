import { useEscape } from './useEscape'

type Props = {
  projectName: string
  /** The project is the one currently open; deleting it leaves the page. */
  isOpen: boolean
  onCancel: () => void
  onConfirm: () => void
}

/** Confirmation before deleting a project. Escape or a click outside cancels. */
export default function DeleteProjectDialog({ projectName, isOpen, onCancel, onConfirm }: Props) {
  useEscape(onCancel)

  return (
    <div className="kap-dialog-overlay" onClick={onCancel}>
      <div className="kap-dialog" role="alertdialog" aria-modal="true" aria-labelledby="kap-delete-title" onClick={(e) => e.stopPropagation()}>
        <div className="kap-dialog-title" id="kap-delete-title">Delete “{projectName}”?</div>
        <div className="kap-dialog-body">
          {isOpen ? 'This project is currently open. ' : ''}It will be permanently deleted. This action cannot be undone.
        </div>
        <div className="kap-dialog-actions">
          <button type="button" className="kap-dialog-cancel" onClick={onCancel} autoFocus>Cancel</button>
          <button type="button" className="kap-dialog-danger" onClick={onConfirm}>Delete project</button>
        </div>
      </div>
    </div>
  )
}
