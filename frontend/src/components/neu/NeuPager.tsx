import { AppIcon } from '../icons'
import NeuIconButton from './NeuIconButton'

type Props = {
  /** Zero-based current page. */
  page: number
  pageCount: number
  onChange: (page: number) => void
}

/** Previous / next with "current / total". Renders nothing for a single page. */
export default function NeuPager({ page, pageCount, onChange }: Props) {
  if (pageCount <= 1) return null
  return (
    <nav className="neu-pager" aria-label="Pagination">
      <NeuIconButton aria-label="Previous page" disabled={page === 0} onClick={() => onChange(page - 1)}>
        <AppIcon name="caretLeft" />
      </NeuIconButton>
      <span className="neu-pager__info" aria-live="polite">{page + 1} / {pageCount}</span>
      <NeuIconButton aria-label="Next page" disabled={page >= pageCount - 1} onClick={() => onChange(page + 1)}>
        <AppIcon name="caretRight" />
      </NeuIconButton>
    </nav>
  )
}
