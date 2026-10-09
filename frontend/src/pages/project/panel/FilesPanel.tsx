import { useMemo } from 'react'
import { type FileNode } from '../../../api/projects'
import { useFileTree, useStagedFiles } from '../../../api/queries'
import { LoadingState } from '../../../components/ui/States'
import { mergeStagedTree } from './stagedTree'

export function FilesPanel({ projectId, activeRunId }: { projectId: string; activeRunId: string | null }) {
  const { data: tree, isLoading } = useFileTree(projectId)
  const { data: staged } = useStagedFiles(projectId, activeRunId, !!activeRunId)
  const visibleTree = useMemo(() => mergeStagedTree(tree ?? [], staged ?? []), [tree, staged])

  function renderNode(node: FileNode, depth = 0): React.ReactNode {
    const isDir = node.dir
    return (
      <div key={node.path} style={{ paddingLeft: depth * 14 }}>
        <div className={'pw-file-row' + (isDir ? ' pw-file-dir' : '')}>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            {isDir
              ? <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
              : <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6" />}
          </svg>
          <span>{node.name}</span>
        </div>
        {isDir && node.children?.map(child => renderNode(child, depth + 1))}
      </div>
    )
  }

  if (isLoading) return <div className="pw-insight"><LoadingState /></div>
  if (!visibleTree.length) return (
    <div className="pw-empty">
      <div>
        <span className="pw-empty-icon">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" /></svg>
        </span>
        <h3>No files yet</h3>
        <p>Files will appear here once the agent starts building.</p>
      </div>
    </div>
  )
  return <div className="pw-file-tree">{visibleTree.map(n => renderNode(n))}</div>
}
