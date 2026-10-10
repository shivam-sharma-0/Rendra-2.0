import { cx } from '../../lib/cx'
import { AppIcon } from '../icons'

type Props = {
  value: string
  onChange: (value: string) => void
  /** Accessible name, e.g. "Search chats". */
  label: string
  placeholder?: string
  autoFocus?: boolean
  className?: string
}

/** Inset search box with a clear button while it has text. */
export default function NeuSearchField({ value, onChange, label, placeholder, autoFocus, className }: Props) {
  return (
    <div className={cx('neu-search neu-well neu-well--deep', className)}>
      <AppIcon name="search" />
      <input type="search" value={value} placeholder={placeholder} aria-label={label} autoFocus={autoFocus}
        onChange={(e) => onChange(e.target.value)} />
      {value && (
        <button type="button" className="neu-search__clear" aria-label="Clear search" onClick={() => onChange('')}>
          <AppIcon name="close" />
        </button>
      )}
    </div>
  )
}
