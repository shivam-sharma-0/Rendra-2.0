import { NeuAlert, NeuButton, NeuDialog } from '../../components/neu'

type Props = {
  projectName: string
  /** The project is the one currently open; deleting it leaves the page. */
  isOpen: boolean
  /** The delete request is running: buttons are disabled and the dialog cannot be dismissed. */
  busy?: boolean
  error?: string
  onCancel: () => void
  onConfirm: () => void
}

/** Confirmation before deleting a project. Escape or a click outside cancels. */
export default function DeleteProjectDialog({ projectName, isOpen, busy = false, error, onCancel, onConfirm }: Props) {
  return (
    <NeuDialog
      title={`Delete “${projectName}”?`}
      size="sm"
      locked={busy}
      onClose={onCancel}
      footer={(
        <>
          <NeuButton onClick={onCancel} disabled={busy}>Cancel</NeuButton>
          <NeuButton variant="danger" onClick={onConfirm} disabled={busy}>{busy ? 'Deleting…' : 'Delete project'}</NeuButton>
        </>
      )}
    >
      <p className="kap-delete-text">{isOpen ? 'This project is currently open. ' : ''}It will be permanently deleted. This action cannot be undone.</p>
      {error && <NeuAlert>{error}</NeuAlert>}
    </NeuDialog>
  )
}
