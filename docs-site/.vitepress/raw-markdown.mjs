import { closeSync, constants, fstatSync, lstatSync, openSync, readFileSync, realpathSync } from 'node:fs'
import { extname, isAbsolute, relative, resolve, sep, win32 } from 'node:path'

function isWithin(root, file) {
  const path = relative(root, file)
  return path !== '' && path !== '..' && !path.startsWith(`..${sep}`) && !isAbsolute(path)
}

export function readRawMarkdown(rootDirectory, requestUrl) {
  if (typeof requestUrl !== 'string' || !requestUrl.startsWith('/md/')) return null

  let descriptor
  try {
    const path = decodeURIComponent(requestUrl.slice(4).split(/[?#]/, 1)[0])
    if (!path || path.includes('\0') || path.includes('\\') ||
        isAbsolute(path) || win32.parse(path).root || path.split('/').includes('..') ||
        extname(path) !== '.md') return null

    const root = realpathSync(rootDirectory)
    const candidate = resolve(root, path)
    if (!isWithin(root, candidate)) return null
    const file = realpathSync(candidate)
    // Links are allowed only when the final target is also a Markdown file inside this root.
    if (!isWithin(root, file) || extname(file) !== '.md') return null
    const before = lstatSync(file)
    if (!before.isFile() || !constants.O_NOFOLLOW || !constants.O_NONBLOCK) return null

    descriptor = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
    const opened = fstatSync(descriptor)
    if (!opened.isFile() || opened.dev !== before.dev || opened.ino !== before.ino) return null
    return readFileSync(descriptor, 'utf8')
  } catch {
    return null
  } finally {
    if (descriptor !== undefined) {
      try { closeSync(descriptor) } catch {}
    }
  }
}

export function createRawMarkdownMiddleware(rootDirectory) {
  return (req, res, next) => {
    const content = readRawMarkdown(rootDirectory, req.url)
    if (content === null) {
      next()
      return
    }
    res.setHeader('Content-Type', 'text/plain; charset=utf-8')
    res.setHeader('Access-Control-Allow-Origin', '*')
    res.end(content)
  }
}
