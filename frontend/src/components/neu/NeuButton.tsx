import type { AnchorHTMLAttributes, ButtonHTMLAttributes } from 'react'
import { neuButtonClass, type NeuButtonStyle } from './neuButtonClass'

type ButtonProps = NeuButtonStyle & ButtonHTMLAttributes<HTMLButtonElement> & { href?: undefined }
type LinkProps = NeuButtonStyle & AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }
export type NeuButtonProps = ButtonProps | LinkProps

const isLink = (props: NeuButtonProps): props is LinkProps => props.href !== undefined

/**
 * Soft-UI button. Renders an `<a>` when `href` is given, otherwise a `<button>`
 * that defaults to `type="button"` so it never submits a form by accident.
 */
export default function NeuButton(props: NeuButtonProps) {
  if (isLink(props)) {
    const { variant, size, pill, block, className, ...anchor } = props
    return <a className={neuButtonClass({ variant, size, pill, block }, className)} {...anchor} />
  }
  const { variant, size, pill, block, className, type = 'button', ...button } = props
  return <button type={type} className={neuButtonClass({ variant, size, pill, block }, className)} {...button} />
}
