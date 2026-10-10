import { AppIcon } from '../../../../components/icons'

/** Short text marks for common file types; anything else gets a plain file icon. */
const BADGES: [RegExp, string, string][] = [
  [/\.tsx?$/, 'TS', 'ts'],
  [/\.(m?js|jsx)$/, 'JS', 'js'],
  [/\.s?css$/, 'CSS', 'css'],
  [/\.json$/, '{ }', 'json'],
  [/\.html?$/, '< >', 'html'],
  [/\.md$/, 'MD', 'md'],
]

/** The type mark shown before a file's name in the tree. */
export function FileIcon({ name }: { name: string }) {
  const lower = name.toLowerCase()
  const badge = BADGES.find(([pattern]) => pattern.test(lower))
  if (!badge) return <span className="ft-icon"><AppIcon name="file" /></span>
  return <span className={`ft-badge ft-badge--${badge[2]}`} aria-hidden>{badge[1]}</span>
}
