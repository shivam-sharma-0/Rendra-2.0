import type { FileNode, StagedFile } from '../../../api/projects'

/** The file tree with a running build's staged (not yet accepted) changes applied on top. */
export function mergeStagedTree(tree: FileNode[], staged: StagedFile[]): FileNode[] {
  const clone = (node: FileNode): FileNode => ({ ...node, children: node.children?.map(clone) ?? null })
  let result = tree.map(clone)
  for (const change of staged) {
    const parts = change.path.replaceAll('\\', '/').split('/').filter(Boolean)
    if (!parts.length) continue
    const apply = (nodes: FileNode[], index: number): FileNode[] => {
      const name = parts[index]
      const at = nodes.findIndex(node => node.name === name)
      const last = index === parts.length - 1
      if (last) {
        if (change.deleted) return at < 0 ? nodes : nodes.filter((_, nodeIndex) => nodeIndex !== at)
        const previous = at >= 0 ? nodes[at] : null
        const next: FileNode = {
          name,
          path: change.path,
          dir: previous?.dir ?? false,
          size: change.content?.length ?? previous?.size ?? 0,
          children: previous?.children ?? null,
        }
        if (at < 0) return [...nodes, next]
        return nodes.map((node, nodeIndex) => nodeIndex === at ? next : node)
      }
      if (at < 0) {
        if (change.deleted) return nodes
        const path = parts.slice(0, index + 1).join('/')
        const created: FileNode = { name, path, dir: true, size: 0, children: apply([], index + 1) }
        return [...nodes, created]
      }
      const current = nodes[at]
      if (!current.dir) return nodes
      const next = { ...current, children: apply(current.children ?? [], index + 1) }
      return nodes.map((node, nodeIndex) => nodeIndex === at ? next : node)
    }
    result = apply(result, 0)
  }
  return result
}
