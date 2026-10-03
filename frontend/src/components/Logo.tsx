type Props = { className?: string; showWordmark?: boolean }

/** Rendra's rotating spectrum mark and wordmark. */
export default function Logo({ className = '', showWordmark = true }: Props) {
  return (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      <span aria-label="Rendra" className="k-mark !h-[24px] !w-[24px]"><i className="!h-[9px] !w-[9px]" /></span>
      {showWordmark && (
        <span className="font-brand text-[17px] font-bold text-text">Rendra</span>
      )}
    </span>
  )
}
