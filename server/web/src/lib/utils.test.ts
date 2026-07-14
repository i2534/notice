import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  normalizeMessagePayload,
  formatTime,
  decodeNoticeMqttPayloadIfEncoded,
  topicForPublish,
  normalizeAndCategorize,
  isAudioUrl,
  renderMarkdown,
} from './utils.js'
import { dedupByContent } from './store.js'

describe('utils', () => {
  describe('normalizeMessagePayload', () => {
    it('parses nested JSON string with title and content', () => {
      const msg = {
        id: 42,
        content: '{"title":"中文标题","content":"中文内容\\u4e2d\\u6587"}',
        topic: 'notice/test',
      }
      const result = normalizeMessagePayload(msg)
      expect(result.title).toBe('中文标题')
      expect(result.content).toBe('中文内容中文')
      expect(result.topic).toBe('notice/test')
      expect(result.id).toBe(42)
    })

    it('handles already parsed object', () => {
      const msg = {
        id: 7,
        content: { title: '标题', content: '内容', client: 'web' },
        topic: 'notice/alert',
      }
      const result = normalizeMessagePayload(msg)
      expect(result.title).toBe('标题')
      expect(result.content).toBe('内容')
      expect(result.client).toBe('web')
      expect(result.topic).toBe('notice/alert')
      expect(result.id).toBe(7)
    })

    it('returns original for non-JSON content', () => {
      const msg = { content: 'plain text', topic: 'notice' }
      const result = normalizeMessagePayload(msg)
      expect(result).toBe(msg)
    })

    it('returns original for invalid JSON', () => {
      const msg = { content: '{not json}', topic: 'notice' }
      const result = normalizeMessagePayload(msg)
      expect(result).toBe(msg)
    })

    it('handles gzip+base64 encoded content (placeholder)', () => {
      // decodeNoticeMqttPayloadIfEncoded handles this, normalize only gets decoded
      const msg = { content: 'already decoded', topic: 'notice' }
      const result = normalizeMessagePayload(msg)
      expect(result.content).toBe('already decoded')
    })
  })

  describe('formatTime', () => {
    it('formats today as HH:MM:SS', () => {
      const today = new Date().toISOString()
      const result = formatTime(today)
      expect(result).toMatch(/^\d{2}:\d{2}:\d{2}$/)
    })

    it('formats other days as MM/DD HH:MM', () => {
      const yesterday = new Date(Date.now() - 86400000).toISOString()
      const result = formatTime(yesterday)
      expect(result).toMatch(/^\d{2}\/\d{2} \d{2}:\d{2}$/)
    })

    it('returns empty for invalid input', () => {
      expect(formatTime('')).toBe('')
      expect(formatTime('invalid')).toBe('')
      expect(formatTime(null as any)).toBe('')
    })
  })

  describe('dedupByContent', () => {
    it('removes duplicates by title+content+minute', () => {
      const now = new Date()
      const list = [
        { id: 1, title: 'A', content: '内容', timestamp: now.toISOString() },
        { id: 2, title: 'A', content: '内容', timestamp: now.toISOString() }, // 同一分钟重复
        { id: 3, title: 'B', content: '不同', timestamp: now.toISOString() },
      ]
      const result = dedupByContent(list)
      expect(result).toHaveLength(2)
      expect(result.map(r => r.id)).toEqual([1, 3])
    })

    it('keeps messages from different minutes', () => {
      const base = new Date('2024-01-01T10:00:00Z')
      const list = [
        { id: 1, title: 'A', content: '内容', timestamp: new Date(base.getTime()).toISOString() },
        { id: 2, title: 'A', content: '内容', timestamp: new Date(base.getTime() + 60000).toISOString() }, // 1分钟后
      ]
      const result = dedupByContent(list)
      expect(result).toHaveLength(2)
    })

    it('handles empty and null input', () => {
      expect(dedupByContent([])).toEqual([])
      expect(dedupByContent(null as any)).toEqual([])
    })
  })

  describe('topicForPublish', () => {
    it('strips # and trailing slashes after #', () => {
      expect(topicForPublish('notice/#')).toBe('notice')
      expect(topicForPublish('notice/test/#')).toBe('notice/test')
      expect(topicForPublish('notice/test/#')).toBe('notice/test') // trailing slash after # stripped
    })

    it('does not strip trailing slashes without #', () => {
      expect(topicForPublish('notice///')).toBe('notice///')
      expect(topicForPublish('notice/test///')).toBe('notice/test///')
    })

    it('replaces + with reply', () => {
      expect(topicForPublish('notice/+')).toBe('notice/reply')
      expect(topicForPublish('a/+/b')).toBe('a/reply/b')
    })
  })

  describe('decodeNoticeMqttPayloadIfEncoded', () => {
    it('returns original if not encoded', async () => {
      const msg = { content: 'plain', content_encoding: undefined }
      const result = await decodeNoticeMqttPayloadIfEncoded(msg)
      expect(result).toBe(msg)
    })

    it('returns original if encoding not gzip+base64', async () => {
      const msg = { content: 'plain', content_encoding: 'identity' }
      const result = await decodeNoticeMqttPayloadIfEncoded(msg)
      expect(result).toBe(msg)
    })
  })
})

describe('normalizeAndCategorize', () => {
  it('preserves id from original msg', () => {
    const r = normalizeAndCategorize({ id: 'abc-123', content: 'hello' })
    expect(r.id).toBe('abc-123')
  })

  it('preserves id even when normalizeMessagePayload drops it (nested JSON content)', () => {
    const r = normalizeAndCategorize({ id: 42, content: { title: 'inner', content: 'nested' } })
    expect(r.id).toBe(42)
    expect(r.title).toBe('inner')
    expect(r.content).toBe('nested')
  })

  it('categorizes as alert when topic contains alert', () => {
    expect(normalizeAndCategorize({ id: 1, content: 'x', topic: 'alert' }).cat).toBe('alert')
  })

  it('categorizes as voice when topic contains voice', () => {
    expect(normalizeAndCategorize({ id: 1, content: 'x', topic: 'notice/voice' }).cat).toBe('voice')
  })

  it('categorizes as system for other topics', () => {
    expect(normalizeAndCategorize({ id: 1, content: 'x', topic: 'notice/info' }).cat).toBe('system')
  })

  it('defaults unread to true', () => {
    expect(normalizeAndCategorize({ id: 1, content: 'x' }).unread).toBe(true)
  })

  it('respects unread=false', () => {
    expect(normalizeAndCategorize({ id: 1, content: 'x', unread: false }).unread).toBe(false)
  })

  it('applies defaults for missing fields', () => {
    const r = normalizeAndCategorize({ id: 1 })
    expect(r.title).toBe('通知')
    expect(r.topic).toBe('notice')
    expect(r.client).toBe('')
  })
})

describe('isAudioUrl', () => {
  it('matches /api/media/ paths', () => {
    expect(isAudioUrl('/api/media?n=test.m4a')).toBe(true)
    expect(isAudioUrl('https://example.com/api/media/file.m4a')).toBe(true)
  })

  it('matches audio file extensions', () => {
    expect(isAudioUrl('file.mp3')).toBe(true)
    expect(isAudioUrl('file.ogg')).toBe(true)
    expect(isAudioUrl('file.wav')).toBe(true)
    expect(isAudioUrl('file.m4a')).toBe(true)
  })

  it('matches via query param n=', () => {
    expect(isAudioUrl('/api/media?n=voice.m4a&e=123')).toBe(true)
  })

  it('rejects non-audio URLs', () => {
    expect(isAudioUrl('/image.png')).toBe(false)
    expect(isAudioUrl('')).toBe(false)
  })

  it('rejects null/undefined/number', () => {
    expect(isAudioUrl(null)).toBe(false)
    expect(isAudioUrl(undefined)).toBe(false)
    expect(isAudioUrl(123)).toBe(false)
  })
})
describe('renderMarkdown', () => {
  it('collapses excessive blank lines before parse', () => {
    const html = renderMarkdown('a\n\n\n\n\nb')
    expect(html.match(/<p>/g)?.length ?? 0).toBeLessThanOrEqual(2)
    expect(html).toContain('a')
    expect(html).toContain('b')
  })

  it('renders simple markdown', () => {
    const html = renderMarkdown('**bold**')
    expect(html).toContain('<strong>bold</strong>')
  })
})
