import { useMemo, useState } from 'react'
import { AppIcon } from '../../../../components/icons'
import { highlightLine } from './highlight'

type CodeViewProps = {
  path: string
  /** null while loading; `binary` files cannot be shown. */
  content: string | null
  binary: boolean
  onClose: () => void
}

/** The open file: its path with copy / download / close, then its lines with numbers. */
export function CodeView({ path, content, binary, onClose }: CodeViewProps) {
  const [copied, setCopied] = useState(false)
  const name = path.split('/').pop() || 'file.txt'

  const copy = async () => {
    if (content === null) return
    try {
      await navigator.clipboard.writeText(content)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch { /* clipboard unavailable */ }
  }
  const download = () => {
    if (content === null) return
    const url = URL.createObjectURL(new Blob([content], { type: 'text/plain' }))
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <article className="cv">
      <header className="cv-head">
        <span className="cv-path" title={path}>{path}</span>
        <button type="button" className="cv-btn" onClick={() => void copy()} disabled={content === null || binary} aria-label={copied ? 'Copied' : 'Copy file'} title={copied ? 'Copied' : 'Copy file'}>
          <AppIcon name={copied ? 'check' : 'copy'} />
        </button>
        <button type="button" className="cv-btn" onClick={download} disabled={content === null || binary} aria-label="Download file" title="Download file">
          <AppIcon name="download" />
        </button>
        <button type="button" className="cv-btn" onClick={onClose} aria-label="Close file" title="Close file">
          <AppIcon name="close" />
        </button>
      </header>
      {content === null
        ? <p className="cv-note">Loading…</p>
        : binary
          ? <p className="cv-note">Binary file — cannot preview.</p>
          : <CodeLines content={content} path={path} />}
    </article>
  )
}

function CodeLines({ content, path }: { content: string; path: string }) {
  const lines = useMemo(() => content.split('\n'), [content])
  const ext = path.split('.').pop()?.toLowerCase() ?? ''
  const [active, setActive] = useState<number | null>(null)
  return (
    <div className="cv-scroll">
      <code className="cv-lines">
        {lines.map((line, i) => (
          <div key={i} className={'cv-line' + (active === i ? ' is-active' : '')} onClick={() => setActive(i)}>
            <i>{i + 1}</i>
            <span>{highlightLine(line, ext)}</span>
          </div>
        ))}
      </code>
    </div>
  )
}
