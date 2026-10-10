import { useRef, type KeyboardEvent } from 'react'
import { cx } from '../../lib/cx'
import { snapTextWidth } from '../../lib/snapTextWidth'
import { useSlidingThumb } from '../../lib/useSlidingThumb'
import { AppIcon, type AppIconName } from '../icons'

export type FoldingOption<T extends string> = { value: T; label: string; icon: AppIconName; /** Shown in the tooltip after the label. */ hint?: string }

type Props<T extends string> = {
  options: readonly FoldingOption<T>[]
  value: T
  onChange: (value: T) => void
  'aria-label': string
  className?: string
}

/**
 * A single-choice switch where only the selected option shows its name; the others are icons with
 * a tooltip. The raised pill slides to the new option while its name unfolds. A radio group: only the
 * selected option is in the tab order and the arrow keys move the selection.
 */
export default function NeuFoldingSwitch<T extends string>({ options, value, onChange, className, ...aria }: Props<T>) {
  const listRef = useRef<HTMLDivElement>(null)
  const thumbRef = useRef<HTMLSpanElement>(null)
  const buttons = useRef<Array<HTMLButtonElement | null>>([])
  const selected = Math.max(0, options.findIndex((o) => o.value === value))
  useSlidingThumb(listRef, thumbRef, `[data-fold-index="${selected}"]`)

  const onKeyDown = (e: KeyboardEvent) => {
    const step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key]
    if (!step) return
    e.preventDefault()
    const next = (selected + step + options.length) % options.length
    onChange(options[next].value)
    buttons.current[next]?.focus()
  }

  return (
    <div ref={listRef} role="radiogroup" className={cx('neu-fold', className)} onKeyDown={onKeyDown} {...aria}>
      <span ref={thumbRef} className="neu-fold__thumb" aria-hidden />
      {options.map((option, i) => {
        const on = i === selected
        return (
          <button
            key={option.value}
            ref={(el) => { buttons.current[i] = el }}
            type="button"
            role="radio"
            aria-checked={on}
            aria-label={option.label}
            tabIndex={on ? 0 : -1}
            title={option.hint ? `${option.label}: ${option.hint}` : option.label}
            data-fold-index={i}
            className={cx('neu-fold__option', !on && 'is-folded')}
            onClick={() => onChange(option.value)}
          >
            <AppIcon name={option.icon} />
            <span ref={snapTextWidth} className="neu-fold__label" aria-hidden>{option.label}</span>
          </button>
        )
      })}
    </div>
  )
}
