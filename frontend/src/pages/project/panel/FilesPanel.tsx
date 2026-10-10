import { useMemo } from 'react'
import { type FileNode } from '../../../api/projects'
import { useFileTree, useStagedFiles } from '../../../api/queries'
import { EmptyState, LoadingState } from '../../../components/ui/States'
import { FileTree } from './code/FileTree'
import { mergeStagedTree } from './stagedTree'

function totals(nodes: FileNode[]) {
  let count = 0
  let bytes = 0
  const walk = (list: FileNode[]) => list.forEach((n) => {
    if (n.dir) { if (n.children) walk(n.children) } else { count++; bytes += n.size }
  })
  walk(nodes)
  return { count, kb: Math.max(1, Math.round(bytes / 1024)) }
}

/** The project's files with their sizes (read-only; the Code view opens them). */
export function FilesPanel({ projectId, activeRunId }: { projectId: string; activeRunId: string | null }) {
  const { data: tree, isLoading } = useFileTree(projectId)
  const { data: staged } = useStagedFiles(projectId, activeRunId, !!activeRunId)
  const nodes = useMemo(() => mergeStagedTree(tree ?? [], staged ?? []), [tree, staged])

  if (isLoading) return <LoadingState label="Loading files…" />
  if (!nodes.length) return <EmptyState title="No files yet" hint="Files will appear here once Rendra starts building." />
  const { count, kb } = totals(nodes)
  return (
    <div className="pv-page">
      <p className="pv-sub">{count} file{count === 1 ? '' : 's'} · {kb} KB</p>
      <div className="pv-well"><FileTree nodes={nodes} showSize /></div>
    </div>
  )
}
