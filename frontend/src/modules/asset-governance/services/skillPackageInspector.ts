interface ZipEntry {
  path: string
  data: Uint8Array
}

const ignoredSegments = new Set(['.git', '.svn', '.hg', 'node_modules', '__pycache__', '__MACOSX'])
const ignoredNames = new Set(['.DS_Store', 'Thumbs.db', 'desktop.ini'])
const encoder = new TextEncoder()

const crcTable = (() => {
  const table = new Uint32Array(256)
  for (let index = 0; index < 256; index += 1) {
    let value = index
    for (let bit = 0; bit < 8; bit += 1) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1
    table[index] = value >>> 0
  }
  return table
})()

function ignored(path: string): boolean {
  const parts = path.replace(/\\/g, '/').split('/').filter(Boolean)
  const name = parts[parts.length - 1] ?? ''
  return parts.some((part) => ignoredSegments.has(part)) || ignoredNames.has(name) || name.startsWith('._') || /\.(?:pyc|swp)$/i.test(name)
}

function crc32(bytes: Uint8Array): number {
  let value = 0xffffffff
  for (const byte of bytes) value = crcTable[(value ^ byte) & 0xff] ^ (value >>> 8)
  return (value ^ 0xffffffff) >>> 0
}

function createStoreZip(entries: ZipEntry[]): Blob {
  const localParts: Uint8Array[] = []
  const centralParts: Uint8Array[] = []
  let offset = 0
  entries.forEach((entry) => {
    const name = encoder.encode(entry.path)
    const checksum = crc32(entry.data)
    const local = new Uint8Array(30 + name.length)
    const localView = new DataView(local.buffer)
    localView.setUint32(0, 0x04034b50, true)
    localView.setUint16(4, 20, true)
    localView.setUint16(6, 0x0800, true)
    localView.setUint16(8, 0, true)
    localView.setUint32(14, checksum, true)
    localView.setUint32(18, entry.data.length, true)
    localView.setUint32(22, entry.data.length, true)
    localView.setUint16(26, name.length, true)
    local.set(name, 30)
    localParts.push(local, entry.data)

    const central = new Uint8Array(46 + name.length)
    const centralView = new DataView(central.buffer)
    centralView.setUint32(0, 0x02014b50, true)
    centralView.setUint16(4, 20, true)
    centralView.setUint16(6, 20, true)
    centralView.setUint16(8, 0x0800, true)
    centralView.setUint16(10, 0, true)
    centralView.setUint32(16, checksum, true)
    centralView.setUint32(20, entry.data.length, true)
    centralView.setUint32(24, entry.data.length, true)
    centralView.setUint16(28, name.length, true)
    centralView.setUint32(42, offset, true)
    central.set(name, 46)
    centralParts.push(central)
    offset += local.length + entry.data.length
  })

  const centralSize = centralParts.reduce((total, part) => total + part.length, 0)
  const end = new Uint8Array(22)
  const endView = new DataView(end.buffer)
  endView.setUint32(0, 0x06054b50, true)
  endView.setUint16(8, entries.length, true)
  endView.setUint16(10, entries.length, true)
  endView.setUint32(12, centralSize, true)
  endView.setUint32(16, offset, true)
  return new Blob([...localParts, ...centralParts, end], { type: 'application/zip' })
}

export async function packageFolderAsZip(fileList: FileList): Promise<File> {
  const files = Array.from(fileList)
  if (files.length === 0) throw new Error('请选择包含 SKILL.md 的文件夹')
  const firstPath = (files[0] as File & { webkitRelativePath?: string }).webkitRelativePath || files[0].name
  const root = firstPath.includes('/') ? firstPath.split('/')[0] : ''
  const entries = (await Promise.all(files.map(async (file): Promise<ZipEntry | null> => {
    const relative = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name
    const path = root && relative.startsWith(`${root}/`) ? relative.slice(root.length + 1) : relative
    if (!path || ignored(path)) return null
    return { path: path.replace(/\\/g, '/'), data: new Uint8Array(await file.arrayBuffer()) }
  }))).filter((entry): entry is ZipEntry => entry !== null).sort((left, right) => left.path.localeCompare(right.path))
  if (!entries.some((entry) => entry.path === 'SKILL.md')) throw new Error('所选文件夹根目录必须包含 SKILL.md')
  return new File([createStoreZip(entries)], `${root || 'skill'}.zip`, { type: 'application/zip' })
}

export function slugFromPackageName(fileName: string): string {
  return fileName.replace(/\.zip$/i, '').toLocaleLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'skill'
}
