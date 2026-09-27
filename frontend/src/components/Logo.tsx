type Props = { className?: string; showWordmark?: boolean }

/** Kiln's rotating spectrum mark and wordmark. */
export default function Logo({ className = '', showWordmark = true }: Props) {
  return (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      <span aria-label="Kiln" className="k-mark !h-[24px] !w-[24px]"><i className="!h-[9px] !w-[9px]" /></span>
      {showWordmark && (
        <span className="font-brand text-[17px] font-bold text-text">Kiln</span>
      )}
    </span>
  )
}
