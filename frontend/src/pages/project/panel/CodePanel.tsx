import { useMemo, useRef, useState } from 'react'
import { useFileContent, useFileTree, useStagedFiles } from '../../../api/queries'
import { AppIcon } from '../../../components/icons'
import { LoadingState } from '../../../components/ui/States'
import { cx } from '../../../lib/cx'
import { CodeView } from './code/CodeView'
import { FileTree } from './code/FileTree'
import { useResizableWidth } from './code/useResizableWidth'
import { mergeStagedTree } from './stagedTree'

/**
 * Code view: a searchable file tree (drag its edge to resize) beside the open file. While a run is
 * building, its staged (not yet committed) files are shown in place of the saved ones. File data is
 * cached by React Query, so switching back to a file is instant.
 */
export function CodePanel({ projectId, activeRunId }: { projectId: string; activeRunId: string | null }) {
  const [selected, setSelected] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)
  const tree = useResizableWidth(containerRef, 'pw-code-sidebar-width', { initial: 240, min: 160, reserve: 220 })

  const { data: files, isError } = useFileTree(projectId)
  const { data: staged } = useStagedFiles(projectId, activeRunId, !!activeRunId)
  const nodes = useMemo(() => mergeStagedTree(files ?? [], staged ?? []), [files, staged])
  const { data: file, isFetching } = useFileContent(projectId, selected)
  const stagedFile = staged?.find((item) => item.path === selected && !item.deleted)

  if (!files && !isError) return <LoadingState label="Loading files…" />
  if (!nodes.length) return <p className="pv-note">No files yet — when Rendra builds in this project, the files it writes show up here.</p>

  const content = stagedFile ? stagedFile.content : isFetching || !file ? null : file.content
  const binary = stagedFile ? stagedFile.content === null : !!file?.binary

  return (
    <div className={cx('code', tree.dragging && 'is-resizing')} ref={containerRef}>
      <aside className="code-files" style={{ width: tree.width }}>
        <label className="code-search">
          <AppIcon name="search" />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Find a file" aria-label="Find a file" />
        </label>
        <FileTree nodes={nodes} selected={selected} onSelect={setSelected} query={query} />
      </aside>
      <div className="code-resizer" role="separator" aria-orientation="vertical" aria-label="Resize the file list" title="Drag to resize · double-click to reset" {...tree.handle} />
      {selected
        ? <CodeView key={selected} path={selected} content={content} binary={binary} onClose={() => setSelected(null)} />
        : <p className="pv-note code-pick">Pick a file to see its code.</p>}
    </div>
  )
}
