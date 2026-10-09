type Props = { onCancel: () => void; onConfirm: () => void }

/** Confirmation before deleting the project that is currently open. */
export default function DeleteProjectDialog({ onCancel, onConfirm }: Props) {
  return (
    <div className="kap-dialog-overlay" onClick={onCancel}>
      <div className="kap-dialog" onClick={(e) => e.stopPropagation()}>
        <div className="kap-dialog-title">Delete active project?</div>
        <div className="kap-dialog-body">
          This project is currently open. Are you sure you want to delete it? This action cannot be undone.
        </div>
        <div className="kap-dialog-actions">
          <button type="button" className="kap-dialog-cancel" onClick={onCancel}>Cancel</button>
          <button type="button" className="kap-dialog-danger" onClick={onConfirm}>Delete project</button>
        </div>
      </div>
    </div>
  )
}
