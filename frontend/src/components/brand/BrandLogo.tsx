import { BRANDS, type BrandId } from './brands'

type Props = {
  brand: BrandId
  size?: number
  className?: string
  /** Set when the logo is the only thing naming the brand; otherwise it is decorative. */
  label?: string
}

/** A third-party brand's official logo. */
export default function BrandLogo({ brand, size = 20, className, label }: Props) {
  const mark = BRANDS[brand]
  return (
    <svg width={size} height={size} viewBox={mark.viewBox} className={className}
      role={label ? 'img' : undefined} aria-label={label} aria-hidden={label ? undefined : true}>
      {mark.paths.map((p, i) => <path key={i} d={p.d} fill={p.fill} />)}
    </svg>
  )
}
