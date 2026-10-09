import { cx } from '../../lib/cx'

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
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
        <path d="M21 21l-4.35-4.35M17 11A6 6 0 1 1 5 11a6 6 0 0 1 12 0z" />
      </svg>
      <input type="search" value={value} placeholder={placeholder} aria-label={label} autoFocus={autoFocus}
        onChange={(e) => onChange(e.target.value)} />
      {value && (
        <button type="button" className="neu-search__clear" aria-label="Clear search" onClick={() => onChange('')}>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden><path d="M18 6L6 18M6 6l12 12" /></svg>
        </button>
      )}
    </div>
  )
}
