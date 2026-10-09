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
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden><path d="m15 18-6-6 6-6" /></svg>
      </NeuIconButton>
      <span className="neu-pager__info" aria-live="polite">{page + 1} / {pageCount}</span>
      <NeuIconButton aria-label="Next page" disabled={page >= pageCount - 1} onClick={() => onChange(page + 1)}>
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden><path d="m9 18 6-6-6-6" /></svg>
      </NeuIconButton>
    </nav>
  )
}
