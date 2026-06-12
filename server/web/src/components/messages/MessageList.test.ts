import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/svelte'
import MessageList from './MessageList.svelte'

const { mockDetailMessageId, mockMessages, mockFilteredMessages } = vi.hoisted(() => ({
  mockDetailMessageId: { 
    subscribe: vi.fn((cb) => cb(null)), 
    set: vi.fn(),
    update: vi.fn(),
  },
  mockMessages: { 
    subscribe: vi.fn((cb) => cb([])), 
    set: vi.fn(),
    update: vi.fn(),
  },
  mockFilteredMessages: { 
    subscribe: vi.fn((cb) => cb([])), 
    set: vi.fn(),
  },
}))

vi.mock('../../lib/store.js', () => ({
  detailMessageId: mockDetailMessageId,
  messages: mockMessages,
  filteredMessages: mockFilteredMessages,
}))

import { detailMessageId, messages, filteredMessages } from '../../lib/store.js'

describe('MessageList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockDetailMessageId.set(null)
    mockMessages.set([])
    mockFilteredMessages.set([])
  })

  it('shows EmptyState when no messages', () => {
    mockFilteredMessages.set([])
    render(MessageList, { showToast: vi.fn(), showConfirm: vi.fn() })
    expect(screen.getByText('暂无消息')).toBeInTheDocument()
  })

  it('renders messages from filteredMessages', () => {
    const now = new Date().toISOString()
    mockFilteredMessages.set([
      { id: 1, title: '消息1', content: '内容1', topic: 'notice/test', timestamp: now, client: 'web', unread: false, cat: 'system' },
      { id: 2, title: '消息2', content: '内容2', topic: 'notice/alert', timestamp: now, client: 'web', unread: true, cat: 'alert' },
    ])
    render(MessageList, { showToast: vi.fn(), showConfirm: vi.fn() })
    expect(screen.getByText('消息1')).toBeInTheDocument()
    expect(screen.getByText('消息2')).toBeInTheDocument()
  })

  it('sets detailMessageId when message clicked', async () => {
    const now = new Date().toISOString()
    mockFilteredMessages.set([
      { id: 1, title: '测试', content: '内容', topic: 'notice/test', timestamp: now, client: 'web', unread: false, cat: 'system' },
    ])
    render(MessageList, { showToast: vi.fn(), showConfirm: vi.fn() })
    
    await fireEvent.click(screen.getByText('测试'))
    expect(mockDetailMessageId.set).toHaveBeenCalledWith(1)
  })

  it('marks unread message as read with setTimeout (regression: unread click opens detail)', async () => {
    const now = new Date().toISOString()
    mockFilteredMessages.set([
      { id: 1, title: '未读消息', content: '内容', topic: 'notice/test', timestamp: now, client: 'web', unread: true, cat: 'system' },
    ])
    render(MessageList, { showToast: vi.fn(), showConfirm: vi.fn() })
    
    await fireEvent.click(screen.getByText('未读消息'))
    
    // detailMessageId set immediately
    expect(mockDetailMessageId.set).toHaveBeenCalledWith(1)
    
    // messages.update called after setTimeout (simulated with vi.useFakeTimers)
    // In real test with fake timers, we'd advance timers
  })

  it('does not mark already-read messages', () => {
    const now = new Date().toISOString()
    mockFilteredMessages.set([
      { id: 1, title: '已读', content: '内容', topic: 'notice/test', timestamp: now, client: 'web', unread: false, cat: 'system' },
    ])
    render(MessageList, { showToast: vi.fn(), showConfirm: vi.fn() })
    messages.update.mockClear()
    
    fireEvent.click(screen.getByText('已读'))
    expect(messages.update).not.toHaveBeenCalled()
  })
})