import { useMemo, useState } from 'react'
import { type FileNode } from '../../../../api/projects'
import { AppIcon } from '../../../../components/icons'
import { cx } from '../../../../lib/cx'
import { FileIcon } from './FileIcon'

type FileTreeProps = {
  nodes: FileNode[]
  /** The open file, highlighted. */
  selected?: string | null
  /** Makes files clickable. Without it the tree is read-only (folders still open and close). */
  onSelect?: (path: string) => void
  /** When set, a flat list of the files whose path matches, instead of the tree. */
  query?: string
  /** Show each file's size. */
  showSize?: boolean
}

const sortNodes = (nodes: FileNode[]) => [...nodes].sort((a, b) => (a.dir !== b.dir ? (a.dir ? -1 : 1) : a.name.localeCompare(b.name)))

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** Folders down to the second level start open. */
function initiallyOpen(nodes: FileNode[]) {
  const open = new Set<string>()
  const walk = (list: FileNode[], depth: number) => list.forEach((n) => {
    if (!n.dir) return
    if (depth <= 1) open.add(n.path)
    if (n.children) walk(n.children, depth + 1)
  })
  walk(nodes, 0)
  return open
}

/** A project's files as a tree of folders (open/close) and files (selectable when `onSelect` is given). */
export function FileTree({ nodes, selected, onSelect, query = '', showSize = false }: FileTreeProps) {
  const [open, setOpen] = useState<Set<string> | null>(null)
  const openDirs = open ?? initiallyOpen(nodes)
  const toggle = (path: string) => {
    const next = new Set(openDirs)
    if (next.has(path)) next.delete(path)
    else next.add(path)
    setOpen(next)
  }

  const matches = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return null
    const found: FileNode[] = []
    const walk = (list: FileNode[]) => list.forEach((n) => {
      if (!n.dir && n.path.toLowerCase().includes(q)) found.push(n)
      if (n.children) walk(n.children)
    })
    walk(nodes)
    return found
  }, [nodes, query])

  const fileRow = (node: FileNode, depth: number, label = node.name) => {
    const content = (
      <>
        <FileIcon name={node.name} />
        <span className="ft-name">{label}</span>
        {showSize && <span className="ft-size">{formatSize(node.size)}</span>}
      </>
    )
    const style = { paddingLeft: 10 + depth * 14 + 18 }
    return onSelect
      ? <button key={node.path} type="button" className={cx('ft-row', selected === node.path && 'is-selected')} style={style} onClick={() => onSelect(node.path)} aria-current={selected === node.path || undefined}>{content}</button>
      : <div key={node.path} className="ft-row ft-row--static" style={style}>{content}</div>
  }

  const render = (node: FileNode, depth: number) => {
    if (!node.dir) return fileRow(node, depth)
    const isOpen = openDirs.has(node.path)
    return (
      <div key={node.path} role="group">
        <button type="button" className={cx('ft-row ft-row--dir', isOpen && 'is-open')} style={{ paddingLeft: 10 + depth * 14 }} aria-expanded={isOpen} onClick={() => toggle(node.path)}>
          <span className="ft-caret"><AppIcon name="caretRight" /></span>
          <span className="ft-icon"><AppIcon name={isOpen ? 'folderOpen' : 'folder'} /></span>
          <span className="ft-name">{node.name}</span>
        </button>
        {isOpen && node.children && sortNodes(node.children).map((child) => render(child, depth + 1))}
      </div>
    )
  }

  if (matches) {
    return (
      <div className="ft">
        {matches.length ? matches.map((n) => fileRow(n, 0, n.path)) : <p className="ft-empty">No files match “{query.trim()}”.</p>}
      </div>
    )
  }
  return <div className="ft">{sortNodes(nodes).map((n) => render(n, 0))}</div>
}
