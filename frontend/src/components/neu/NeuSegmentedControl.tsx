import { useRef, type CSSProperties, type KeyboardEvent, type ReactNode } from 'react'
import { cx } from '../../lib/cx'

/** `title` names an option whose label is only an icon (used as its accessible name and tooltip). */
export type SegmentOption<T extends string> = { value: T; label: ReactNode; title?: string }

type Props<T extends string> = {
  options: readonly SegmentOption<T>[]
  value: T
  onChange: (value: T) => void
  'aria-label': string
  className?: string
}

/**
 * A single-choice switch: an inset track with a raised thumb that slides to the
 * selected option. Exposed as a radio group, so only the selected option is in
 * the tab order and the arrow keys move the selection.
 */
export default function NeuSegmentedControl<T extends string>({ options, value, onChange, className, ...aria }: Props<T>) {
  const refs = useRef<Array<HTMLButtonElement | null>>([])
  const selected = Math.max(0, options.findIndex((o) => o.value === value))

  const onKeyDown = (e: KeyboardEvent) => {
    const step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key]
    if (!step) return
    e.preventDefault()
    const next = (selected + step + options.length) % options.length
    onChange(options[next].value)
    refs.current[next]?.focus()
  }

  const thumb = { '--neu-seg-count': options.length, '--neu-seg-index': selected } as CSSProperties

  return (
    <div role="radiogroup" className={cx('neu-segmented', className)} style={thumb} onKeyDown={onKeyDown} {...aria}>
      <span className="neu-segmented__thumb" aria-hidden />
      {options.map((option, i) => (
        <button
          key={option.value}
          ref={(el) => { refs.current[i] = el }}
          type="button"
          role="radio"
          aria-checked={i === selected}
          tabIndex={i === selected ? 0 : -1}
          className="neu-segmented__option"
          aria-label={option.title}
          title={option.title}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}
