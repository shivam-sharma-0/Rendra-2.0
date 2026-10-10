import type { ReactNode } from 'react'

/** Light syntax colouring for one line of JSON or JS/TS (classes hl-* in panels.css). Other files are plain. */
export function highlightLine(line: string, ext: string): ReactNode {
  if (!line) return ' '
  if (ext === 'json') {
    const parts: ReactNode[] = []
    const regex = /("(?:\\.|[^"\\])*"(?:\s*:)?)|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|(\b(?:true|false|null)\b)|([{}[\],:])|(\s+)|([^"0-9a-zA-Z\s{}[\],:]+)/g
    let match: RegExpExecArray | null
    let idx = 0

    while ((match = regex.exec(line)) !== null) {
      const [full, str, num, bool, punct, space, other] = match
      if (str) {
        if (str.endsWith(':')) {
          const key = str.slice(0, -1)
          parts.push(<span key={idx++} className="hl-key">{key}</span>)
          parts.push(<span key={idx++} className="hl-punct">:</span>)
        } else {
          parts.push(<span key={idx++} className="hl-str">{str}</span>)
        }
      } else if (num) {
        parts.push(<span key={idx++} className="hl-num">{num}</span>)
      } else if (bool) {
        parts.push(<span key={idx++} className="hl-bool">{bool}</span>)
      } else if (punct) {
        parts.push(<span key={idx++} className="hl-punct">{punct}</span>)
      } else if (space) {
        parts.push(space)
      } else {
        parts.push(other || full)
      }
    }
    return parts.length ? parts : line
  }

  if (['ts', 'tsx', 'js', 'jsx'].includes(ext)) {
    const parts: ReactNode[] = []
    const regex = /(\/\/[^\n]*|\/\*[\s\S]*?\*\/)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)|(\b(?:import|export|from|default|function|const|let|var|return|if|else|switch|case|break|for|while|try|catch|finally|throw|class|extends|interface|type|async|await|new|this|typeof|instanceof)\b)|(\b(?:true|false|null|undefined|NaN)\b)|(-?\d+(?:\.\d+)?)|([{}()[\].,;:?])|(\s+)|([^\s"'`{}[\]().,;:?]+)/g
    let match: RegExpExecArray | null
    let idx = 0

    while ((match = regex.exec(line)) !== null) {
      const [full, comment, str, kw, bool, num, punct, space, other] = match
      if (comment) {
        parts.push(<span key={idx++} className="hl-comment">{comment}</span>)
      } else if (str) {
        parts.push(<span key={idx++} className="hl-str">{str}</span>)
      } else if (kw) {
        parts.push(<span key={idx++} className="hl-kw">{kw}</span>)
      } else if (bool) {
        parts.push(<span key={idx++} className="hl-bool">{bool}</span>)
      } else if (num) {
        parts.push(<span key={idx++} className="hl-num">{num}</span>)
      } else if (punct) {
        parts.push(<span key={idx++} className="hl-punct">{punct}</span>)
      } else if (space) {
        parts.push(space)
      } else {
        parts.push(other || full)
      }
    }
    return parts.length ? parts : line
  }

  return line
}
