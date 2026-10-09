import { useEffect, useMemo, useRef, useState } from 'react'
import { type FileNode } from '../../../api/projects'
import { useFileContent, useFileTree, useStagedFiles } from '../../../api/queries'
import { Ico } from '../../../rendra/parts'
import { CHECK_ICON, COPY_ICON } from '../icons'
import { clamp } from '../layout'
import { mergeStagedTree } from './stagedTree'

/* ── Code / files ─────────────────────────────────────────────────────────── */
/** Cached via React Query (see useFileTree/useFileContent in api/queries.ts) instead of a raw fetch
 * effect — switching between files you've already opened shows the cached content instantly, with no
 * network round trip, and a build only invalidates this project's file queries (see ProjectPage's
 * onRealtime), not a manually-threaded refreshKey. */
function sortNodes(nodes: FileNode[]): FileNode[] {
  return [...nodes].sort((a, b) => {
    if (a.dir !== b.dir) return a.dir ? -1 : 1
    return a.name.localeCompare(b.name)
  })
}

function FileIcon({ name }: { name: string }) {
  const lower = name.toLowerCase()
  if (lower.endsWith('.json')) {
    return <span className="pw-badge-icon pw-badge-json">{'{ }'}</span>
  }
  if (lower.endsWith('.tsx') || lower.endsWith('.jsx')) {
    return (
      <svg className="pw-tree-svg react" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <ellipse cx="12" cy="12" rx="10" ry="4" />
        <ellipse cx="12" cy="12" rx="10" ry="4" transform="rotate(60 12 12)" />
        <ellipse cx="12" cy="12" rx="10" ry="4" transform="rotate(120 12 12)" />
        <circle cx="12" cy="12" r="1.5" fill="currentColor" />
      </svg>
    )
  }
  if (lower.endsWith('.ts')) {
    return <span className="pw-badge-icon pw-badge-ts">TS</span>
  }
  if (lower.endsWith('.js') || lower.endsWith('.mjs')) {
    return <span className="pw-badge-icon pw-badge-js">JS</span>
  }
  if (lower.endsWith('.css') || lower.endsWith('.scss')) {
    return <span className="pw-badge-icon pw-badge-css">CSS</span>
  }
  if (lower.includes('git')) {
    return (
      <svg className="pw-tree-svg git" width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
        <path d="M12 2a2 2 0 0 0-1.4.6l-8 8a2 2 0 0 0 0 2.8l8 8a2 2 0 0 0 2.8 0l8-8a2 2 0 0 0 0-2.8l-8-8A2 2 0 0 0 12 2zm0 4a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zm-3.5 5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zm7 0a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z" />
      </svg>
    )
  }
  if (lower.includes('prettier') || lower.includes('eslint')) {
    return (
      <svg className="pw-tree-svg config" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <polygon points="12 2 21 7 21 17 12 22 3 17 3 7" />
        <circle cx="12" cy="12" r="2" />
      </svg>
    )
  }
  if (lower.includes('lock') || lower.includes('bun')) {
    return (
      <svg className="pw-tree-svg lock" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
        <path d="M7 11V7a5 5 0 0 1 10 0v4" />
      </svg>
    )
  }
  return (
    <svg className="pw-tree-svg doc" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <polyline points="14 2 14 8 20 8" />
    </svg>
  )
}

export function CodePanel({ projectId, activeRunId }: { projectId: string; activeRunId: string | null }) {
  const [selected, setSelected] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [copied, setCopied] = useState(false)
  const [openDirs, setOpenDirs] = useState<Set<string>>(() => new Set())
  const initializedDirs = useRef(false)

  const [sidebarWidth, setSidebarWidth] = useState(() => {
    try {
      const saved = localStorage.getItem('pw-code-sidebar-width')
      if (saved) {
        const n = parseInt(saved, 10)
        if (!isNaN(n) && n >= 140 && n <= 700) return n
      }
    } catch { /* ignore */ }
    return 240
  })
  const [resizing, setResizing] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  const { data: tree, isError: treeErrored } = useFileTree(projectId)
  const { data: staged } = useStagedFiles(projectId, activeRunId, !!activeRunId)
  const visibleTree = useMemo(() => mergeStagedTree(tree ?? [], staged ?? []), [tree, staged])
  const { data: file, isFetching: loadingFile } = useFileContent(projectId, selected)
  const stagedFile = staged?.find((item) => item.path === selected && !item.deleted)

  // Expand top-level directories by default on first load
  useEffect(() => {
    if (visibleTree.length && !initializedDirs.current) {
      initializedDirs.current = true
      const toOpen = new Set<string>()
      const collect = (nodes: FileNode[], depth = 0) => {
        for (const n of nodes) {
          if (n.dir) {
            if (depth <= 1) toOpen.add(n.path)
            if (n.children) collect(n.children, depth + 1)
          }
        }
      }
      collect(visibleTree)
      setOpenDirs(toOpen)
    }
  }, [visibleTree])

  const toggleDir = (dirPath: string) => {
    setOpenDirs((prev) => {
      const next = new Set(prev)
      if (next.has(dirPath)) next.delete(dirPath)
      else next.add(dirPath)
      return next
    })
  }

  const allFiles = useMemo(() => {
    if (!visibleTree) return []
    const flat: FileNode[] = []
    const collect = (nodes: FileNode[]) => {
      for (const n of nodes) {
        if (!n.dir) flat.push(n)
        if (n.children) collect(n.children)
      }
    }
    collect(visibleTree)
    return flat
  }, [visibleTree])

  const matchingFiles = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return []
    return allFiles.filter((f) => f.path.toLowerCase().includes(q) || f.name.toLowerCase().includes(q))
  }, [allFiles, query])

  const copy = async () => {
    if (!file) return
    try { await navigator.clipboard.writeText(file.content); setCopied(true); setTimeout(() => setCopied(false), 1500) } catch { /* ignore */ }
  }
  const download = () => {
    if (!file) return
    const blob = new Blob([file.content], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = selected?.split('/').pop() || 'file.txt'
    a.click()
    URL.revokeObjectURL(url)
  }

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    e.preventDefault()
    setResizing(true)
    e.currentTarget.setPointerCapture(e.pointerId)
  }

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!resizing) return
    const container = containerRef.current
    if (!container) return
    const rect = container.getBoundingClientRect()
    const raw = e.clientX - rect.left
    const minWidth = 160
    const maxWidth = Math.max(minWidth, rect.width - 200)
    setSidebarWidth(clamp(raw, minWidth, maxWidth))
  }

  const handlePointerUp = () => {
    if (resizing) {
      setResizing(false)
      try {
        localStorage.setItem('pw-code-sidebar-width', String(Math.round(sidebarWidth)))
      } catch { /* ignore */ }
    }
  }

  const renderNode = (node: FileNode, depth = 0): React.ReactNode => {
    if (node.dir) {
      const isOpen = openDirs.has(node.path)
      const sortedChildren = node.children ? sortNodes(node.children) : []
      return (
        <div key={node.path}>
          <div
            className={`pw-file-row pw-file-dir${isOpen ? ' open' : ''}`}
            style={{ paddingLeft: 8 + depth * 14 }}
            onClick={() => toggleDir(node.path)}
          >
            <span className="pw-tree-arrow">
              <svg
                className={`pw-chevron-icon${isOpen ? ' open' : ''}`}
                width="11"
                height="11"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <polyline points="9 18 15 12 9 6" />
              </svg>
            </span>
            <span className="pw-file-name">{node.name}</span>
            <button
              type="button"
              className="pw-file-more"
              onClick={(e) => e.stopPropagation()}
              title="Options"
            >
              ⋯
            </button>
          </div>
          {isOpen && (
            <div>
              {sortedChildren.map((child) => renderNode(child, depth + 1))}
            </div>
          )}
        </div>
      )
    }

    const isSelected = selected === node.path
    return (
      <div
        key={node.path}
        className={`pw-file-row pw-file-item${isSelected ? ' selected' : ''}`}
        style={{ paddingLeft: 8 + depth * 14 + 14 }}
        onClick={() => setSelected(node.path)}
      >
        <FileIcon name={node.name} />
        <span className="pw-file-name">{node.name}</span>
      </div>
    )
  }

  if (!tree && !treeErrored) return <div className="pw-code"><div className="pw-loading">Loading files…</div></div>
  if (!visibleTree || visibleTree.length === 0) {
    return <div className="pw-code"><div className="pw-emptyfiles">No files yet — when Rendra builds in this project, the files it writes show up here.</div></div>
  }

  const sortedTree = sortNodes(visibleTree)

  return (
    <div
      className={`pw-code${resizing ? ' resizing' : ''}`}
      ref={containerRef}
      style={{ userSelect: resizing ? 'none' : undefined }}
    >
      <aside style={{ width: sidebarWidth, minWidth: sidebarWidth, maxWidth: sidebarWidth }}>
        <div className="pw-code-search-wrap">
          <div className="pw-code-search-box">
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search code"
              aria-label="Search code"
            />
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
              <path d="m7 15 5 5 5-5M7 9l5-5 5 5" />
            </svg>
          </div>
        </div>

        {query.trim() ? (
          matchingFiles.length === 0 ? (
            <div className="pw-explorer-empty">No files match “{query}”.</div>
          ) : (
            matchingFiles.map((f) => (
              <div
                key={f.path}
                className={`pw-file-row pw-file-item${selected === f.path ? ' selected' : ''}`}
                style={{ paddingLeft: 12 }}
                onClick={() => setSelected(f.path)}
              >
                <FileIcon name={f.name} />
                <span className="pw-file-name">{f.path}</span>
              </div>
            ))
          )
        ) : (
          sortedTree.map((node) => renderNode(node, 0))
        )}
      </aside>

      <div
        className={`pw-code-resizer${resizing ? ' dragging' : ''}`}
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize files sidebar"
        title="Drag to resize file list"
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
        onDoubleClick={() => {
          setSidebarWidth(240)
          try { localStorage.setItem('pw-code-sidebar-width', '240') } catch {}
        }}
      />

      {!selected ? (
        <div className="pw-code-empty-pane" />
      ) : (
        <article>
          <div className="pw-code-tabbar">
            <div className="pw-code-tab">
              <span className="pw-code-tab-name">{selected}</span>
              <button
                type="button"
                className="pw-code-tab-close"
                onClick={() => setSelected(null)}
                title="Close file"
                aria-label="Close file"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            </div>
            <div className="pw-code-tabbar-actions">
              <button
                type="button"
                className="pw-code-icon-btn"
                onClick={() => void copy()}
                title={copied ? 'Copied' : 'Copy code'}
              >
                <Ico d={copied ? CHECK_ICON : COPY_ICON} size={14} />
              </button>
              <button
                type="button"
                className="pw-code-action-btn"
                onClick={download}
                title="Download file"
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" />
                </svg>
                <span>Download</span>
              </button>
            </div>
          </div>
          {loadingFile ? (
            <div className="pw-loading">Loading…</div>
          ) : file?.binary || (stagedFile && stagedFile.content === null) ? (
            <div className="pw-emptyfiles">Binary file — cannot preview.</div>
          ) : (
            <CodeLines content={stagedFile?.content ?? file?.content ?? ''} filename={selected} />
          )}
        </article>
      )}
    </div>
  )
}

function highlightLine(line: string, ext: string): React.ReactNode {
  if (!line) return ' '
  if (ext === 'json') {
    const parts: React.ReactNode[] = []
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
    const parts: React.ReactNode[] = []
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

function CodeLines({ content, filename }: { content: string; filename: string }) {
  const lines = useMemo(() => content.split('\n'), [content])
  const [activeLine, setActiveLine] = useState<number | null>(() => (lines.length >= 5 ? 5 : lines.length > 0 ? 1 : null))
  const ext = useMemo(() => filename.split('.').pop()?.toLowerCase() ?? '', [filename])

  return (
    <div className="pw-codeview">
      <code className="pw-codelines">
        {lines.map((line, i) => {
          const lineNum = i + 1
          const isActive = activeLine === lineNum
          return (
            <div
              key={i}
              className={`pw-codeline${isActive ? ' active-line' : ''}`}
              onClick={() => setActiveLine(lineNum)}
            >
              <i className="pw-line-num">{lineNum}</i>
              <span className="pw-line-text">{highlightLine(line, ext)}</span>
            </div>
          )
        })}
      </code>
    </div>
  )
}
