import { marked } from 'marked'
import DOMPurify from 'dompurify'

/** 订阅主题转可发布主题：notice/# -> notice */
export function topicForPublish(topic) {
  topic = topic.trim()
  let i = topic.indexOf('#')
  if (i >= 0) topic = topic.substring(0, i).trim().replace(/\/+$/, '') || 'notice'
  if (topic.indexOf('+') >= 0) {
    topic = topic.split('/').map(p => p === '+' ? 'reply' : p).join('/')
  }
  return topic
}

export function escapeHtml(text) {
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}

const NOTICE_CONTENT_ENCODING_GZIP_B64 = 'gzip+base64'

/** 若 MQTT JSON 带 gzip+base64 编码的 content，解压为明文 */
export async function decodeNoticeMqttPayloadIfEncoded(msg) {
  if (!msg || msg.content_encoding !== NOTICE_CONTENT_ENCODING_GZIP_B64 || msg.content == null) return msg
  if (typeof DecompressionStream === 'undefined') return msg
  try {
    const b64 = String(msg.content)
    const binStr = atob(b64)
    const bytes = new Uint8Array(binStr.length)
    for (let i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i)
    const ds = new DecompressionStream('gzip')
    const stream = new Blob([bytes]).stream().pipeThrough(ds)
    const buf = await new Response(stream).arrayBuffer()
    const dec = new TextDecoder('utf-8').decode(buf)
    const out = { ...msg }
    delete out.content_encoding
    out.content = dec
    return out
  } catch { return msg }
}

/** gzip+base64 压缩（发送时用） */
export async function gzipBase64Encode(plain) {
  if (typeof CompressionStream === 'undefined') return null
  try {
    const utf8 = new TextEncoder().encode(plain)
    const cs = new CompressionStream('gzip')
    const stream = new Blob([utf8]).stream().pipeThrough(cs)
    const buf = await new Response(stream).arrayBuffer()
    const bytes = new Uint8Array(buf)
    let bin = ''
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i])
    return btoa(bin)
  } catch { return null }
}

/** 若 content 是嵌套 JSON 字符串，展开显示 */
export function normalizeMessagePayload(msg) {
  let c = msg.content
  if (c && typeof c === 'object' && (c.title !== undefined || c.content !== undefined)) {
    const out = { title: c.title, content: c.content, client: c.client, timestamp: c.timestamp }
    if (msg.topic) out.topic = msg.topic
    return out
  }
  const raw = (c != null) ? String(c).trim().replace(/^\uFEFF/, '') : ''
  if (raw.length < 10) return msg
  const start = raw.indexOf('{')
  if (start === -1) return msg
  const end = raw.lastIndexOf('}')
  if (end <= start) return msg
  const substr = raw.slice(start, end + 1)
  if (substr.indexOf('"content"') === -1 && substr.indexOf('"title"') === -1) return msg
  try {
    const parsed = JSON.parse(substr)
    if (parsed && (parsed.title !== undefined || parsed.content !== undefined)) {
      if (msg.topic) parsed.topic = msg.topic
      return parsed
    }
  } catch {}
  return msg
}

/** 判断 URL 是否为可内联播放的音频 */
export function isAudioUrl(url) {
  if (!url || typeof url !== 'string') return false
  const decoded = url.replace(/&amp;/gi, '&')
  const pathPart = decoded.split('#')[0].split('?')[0]
  if (/\/api\/media(\/|\?|$)/i.test(pathPart)) return true
  if (/\.(mp3|ogg|wav|m4a|aac|opus|webm)$/i.test(pathPart)) return true
  const qs = decoded.split('?')[1] || ''
  if (qs) {
    for (const part of qs.split('&')) {
      const eq = part.indexOf('=')
      if (eq > 0 && part.slice(0, eq).toLowerCase() === 'n') {
        const val = decodeURIComponent(part.slice(eq + 1))
        if (/\.(mp3|ogg|wav|m4a|aac|opus|webm)$/i.test(val)) return true
        break
      }
    }
  }
  return false
}

/** Markdown 渲染 + XSS 过滤 */
export function renderMarkdown(text) {
  if (text == null || text === '') return ''
  try {
    const raw = marked.parse(String(text), { gfm: true, breaks: true })
    const withAudio = raw.replace(/<a\s+href="([^"]+)"[^>]*>[\s\S]*?<\/a>/gi, (match, href) => {
      if (isAudioUrl(href)) {
        return `<audio controls preload="metadata" src="${escapeHtml(href)}"></audio>`
      }
      return match
    })
    return DOMPurify.sanitize(withAudio, {
      ALLOWED_TAGS: ['p','br','strong','em','s','code','pre','ul','ol','li','a','img','audio','blockquote','h1','h2','h3','hr','table','thead','tbody','tr','th','td'],
      ALLOWED_ATTR: ['href','title','src','alt','controls','preload'],
    })
  } catch {
    return escapeHtml(text)
  }
}

/** 格式化时间 */
export function formatTime(isoString) {
  if (!isoString) return ''
  const d = new Date(isoString)
  if (isNaN(d.getTime())) return ''
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  return sameDay
    ? d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
    : d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}
