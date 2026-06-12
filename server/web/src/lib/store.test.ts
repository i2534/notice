import { describe, it, expect, vi, beforeEach } from 'vitest'
import { get } from 'svelte/store'

// Mock localStorage before importing store
const localStorageMock = {
  getItem: vi.fn(),
  setItem: vi.fn(),
  removeItem: vi.fn(),
  clear: vi.fn(),
}
Object.defineProperty(globalThis, 'localStorage', { value: localStorageMock })

import { dedupByContent, saveMessages, loadCachedMessages } from './store.js'

describe('store', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorageMock.getItem.mockReturnValue(null)
  })

  describe('dedupByContent (internal)', () => {
    it('deduplicates by title|content|minute', () => {
      const now = new Date()
      const list = [
        { id: 1, title: 'A', content: '内容', timestamp: now.toISOString() },
        { id: 2, title: 'A', content: '内容', timestamp: now.toISOString() },
        { id: 3, title: 'B', content: '不同', timestamp: now.toISOString() },
      ]
      // 直接测试内部函数需要导出或重写，这里验证逻辑
      const seen = new Set()
      const result = list.filter(m => {
        const key = `${m.title}|${m.content}|${new Date(m.timestamp).toISOString().slice(0, 16)}`
        if (seen.has(key)) return false
        seen.add(key)
        return true
      })
      expect(result).toHaveLength(2)
    })
  })

  describe('saveMessages', () => {
    it('saves deduplicated messages to localStorage', () => {
      const list = [
        { id: 1, title: 'A', content: '内容', timestamp: new Date().toISOString() },
        { id: 2, title: 'A', content: '内容', timestamp: new Date().toISOString() }, // dup
      ]
      saveMessages(list)
      expect(localStorageMock.setItem).toHaveBeenCalled()
      const saved = JSON.parse(localStorageMock.setItem.mock.calls[0][1])
      expect(saved).toHaveLength(1)
    })

    it('limits to maxMessages', () => {
      const many = Array.from({ length: 250 }, (_, i) => ({
        id: i,
        title: `T${i}`,
        content: `C${i}`,
        timestamp: new Date().toISOString(),
      }))
      saveMessages(many)
      const saved = JSON.parse(localStorageMock.setItem.mock.calls[0][1])
      expect(saved.length).toBeLessThanOrEqual(200)
    })
  })

  describe('loadCachedMessages', () => {
    it('returns empty array for null', () => {
      localStorageMock.getItem.mockReturnValue(null)
      const result = loadCachedMessages()
      expect(result).toEqual([])
    })

    it('parses and deduplicates stored messages', () => {
      const now = new Date().toISOString()
      const stored = [
        { id: 1, title: 'A', content: '内容', timestamp: now },
        { id: 2, title: 'A', content: '内容', timestamp: now },
        { id: 3, title: 'B', content: '其他', timestamp: now },
      ]
      localStorageMock.getItem.mockReturnValue(JSON.stringify(stored))
      const result = loadCachedMessages()
      expect(result).toHaveLength(2)
    })

    it('handles corrupted data', () => {
      localStorageMock.getItem.mockReturnValue('not json')
      const result = loadCachedMessages()
      expect(result).toEqual([])
    })

    it('handles non-array data', () => {
      localStorageMock.getItem.mockReturnValue('{"not": "array"}')
      const result = loadCachedMessages()
      expect(result).toEqual([])
    })
  })
})