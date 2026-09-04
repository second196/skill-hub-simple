export interface FileTreeRow {
  key: string
  name: string
  path: string
  parent: string
  depth: number
  directory: boolean
}

export function buildFileTree(paths: string[]): FileTreeRow[] {
  const directories = new Set<string>()
  paths.forEach((path) => {
    const segments = path.split('/').filter(Boolean)
    for (let index = 1; index < segments.length; index += 1) directories.add(segments.slice(0, index).join('/'))
  })
  const all = [
    ...Array.from(directories).map((path) => ({ path, directory: true })),
    ...paths.map((path) => ({ path, directory: false }))
  ]
  return all.sort((left, right) => {
    const leftParent = left.path.split('/').slice(0, -1).join('/')
    const rightParent = right.path.split('/').slice(0, -1).join('/')
    if (leftParent === rightParent && left.directory !== right.directory) return left.directory ? -1 : 1
    return left.path.localeCompare(right.path)
  }).map(({ path, directory }) => {
    const segments = path.split('/').filter(Boolean)
    return {
      key: `${directory ? 'directory' : 'file'}:${path}`,
      name: segments[segments.length - 1] ?? path,
      path,
      parent: segments.slice(0, -1).join('/'),
      depth: Math.max(0, segments.length - 1),
      directory
    }
  })
}
