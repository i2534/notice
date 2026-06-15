import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/svelte'
import FilterBar from './FilterBar.svelte'

const { mockCurrentFilter, mockFilteredMessages, mockSelectedIds } = vi.hoisted(() => ({
  mockCurrentFilter: { 
    subscribe: vi.fn((cb) => { cb('all'); return () => {}; }), 
    set: vi.fn(),
    update: vi.fn(),
  },
  mockFilteredMessages: { 
    subscribe: vi.fn((cb) => { cb([
      { id: 1, title: 'A', content: '内容', timestamp: new Date().toISOString() },
      { id: 2, title: 'B', content: '内容', timestamp: new Date().toISOString() },
      { id: 3, title: 'C', content: '内容', timestamp: new Date().toISOString() },
    ]); return () => {}; }), 
    set: vi.fn(),
  },
  mockSelectedIds: { 
    subscribe: vi.fn((cb) => { cb(new Set()); return () => {}; }), 
    set: vi.fn(),
    update: vi.fn(),
  },
}))

vi.mock('../../lib/store.js', () => ({
  currentFilter: mockCurrentFilter,
  filteredMessages: mockFilteredMessages,
  selectedIds: mockSelectedIds,
}))

describe('FilterBar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCurrentFilter.set('all')
    mockSelectedIds.set(new Set())
  })

  it('renders all filter pills', () => {
    render(FilterBar)
    expect(screen.getByText('全部')).toBeInTheDocument()
    expect(screen.getByText('未读')).toBeInTheDocument()
    expect(screen.getByText('今天')).toBeInTheDocument()
    expect(screen.getByText('通知')).toBeInTheDocument()
    expect(screen.getByText('警告')).toBeInTheDocument()
    expect(screen.getByText('语音')).toBeInTheDocument()
  })

  it('highlights active filter', () => {
    mockCurrentFilter.set('unread')
    render(FilterBar)
    expect(screen.getByText('未读')).toHaveClass('active')
  })

  it('sets currentFilter on pill click', async () => {
    render(FilterBar)
    await fireEvent.click(screen.getByText('未读'))
    expect(mockCurrentFilter.set).toHaveBeenCalledWith('unread')
  })

  it('shows correct count from filteredMessages', () => {
    mockFilteredMessages.set([
      { id: 1 }, { id: 2 }, { id: 3 }
    ])
    render(FilterBar)
    expect(screen.getByText('3 条消息')).toBeInTheDocument()
  })

  it('select all adds all filtered message IDs (regression: Set mutation fix)', async () => {
    mockFilteredMessages.set([
      { id: 1, title: 'A' },
      { id: 2, title: 'B' },
      { id: 3, title: 'C' },
    ])
    mockSelectedIds.set(new Set())
    render(FilterBar)
    
    await fireEvent.click(screen.getByText('全选'))
    
    // Verify selectedIds.update was called with new Set containing all IDs
    expect(mockSelectedIds.update).toHaveBeenCalled()
    const updateFn = mockSelectedIds.update.mock.calls[0][0]
    const current = new Set()
    const result = updateFn(current)
    expect(result).toEqual(new Set([1, 2, 3]))
    // Ensure it's a NEW Set instance (not mutating original)
    expect(result).not.toBe(current)
  })

  it('deselect all removes all IDs', async () => {
    mockFilteredMessages.set([{ id: 1 }, { id: 2 }])
    mockSelectedIds.set(new Set([1, 2]))
    render(FilterBar)
    
    await fireEvent.click(screen.getByText('全选'))
    
    const updateFn = mockSelectedIds.update.mock.calls[0][0]
    const current = new Set([1, 2])
    const result = updateFn(current)
    expect(result.size).toBe(0)
  })
})