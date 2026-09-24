type Props = { className?: string; showWordmark?: boolean }

/** Brand mark: the Rendra logo image, plus an optional wordmark. */
export default function Logo({ className = '', showWordmark = true }: Props) {
  return (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      <img src="/rendra-logo.svg" alt="Rendra" className="h-[18px] w-[18px] object-contain" />
      {showWordmark && (
        <span className="font-brand text-[17px] font-bold text-text">Rendra</span>
      )}
    </span>
  )
}
