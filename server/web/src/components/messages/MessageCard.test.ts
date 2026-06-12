import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/svelte'
import MessageCard from './MessageCard.svelte'

const { mockSelectedIds } = vi.hoisted(() => ({
  mockSelectedIds: { 
    subscribe: vi.fn((cb) => cb(new Set())), 
    update: vi.fn(), 
    set: vi.fn(),
  },
}))

vi.mock('../../lib/store.js', () => ({
  selectedIds: mockSelectedIds,
}))

import { selectedIds } from '../../lib/store.js'

describe('MessageCard', () => {
  const baseMsg = {
    id: 1,
    topic: 'notice/test',
    title: '测试标题',
    content: '测试内容',
    timestamp: new Date().toISOString(),
    client: 'web',
    unread: true,
    cat: 'system'
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders title, topic, client, time, and content preview', () => {
    render(MessageCard, { msg: baseMsg, onclick: vi.fn() })
    expect(screen.getByText('测试标题')).toBeInTheDocument()
    expect(screen.getByText('notice/test')).toBeInTheDocument()
    expect(screen.getByText('web')).toBeInTheDocument()
    expect(screen.getByText('测试内容')).toBeInTheDocument()
  })

  it('shows unread dot when unread=true', () => {
    const { container } = render(MessageCard, { msg: { ...baseMsg, unread: true }, onclick: vi.fn() })
    expect(container.querySelector('.unread-dot')).toBeInTheDocument()
  })

  it('hides unread dot when unread=false', () => {
    const { container } = render(MessageCard, { msg: { ...baseMsg, unread: false }, onclick: vi.fn() })
    expect(container.querySelector('.unread-dot')).not.toBeInTheDocument()
  })

  it('calls onclick when card is clicked', async () => {
    const onclick = vi.fn()
    render(MessageCard, { msg: baseMsg, onclick })
    await fireEvent.click(screen.getByText('测试标题'))
    expect(onclick).toHaveBeenCalled()
  })

  it('toggles selection on checkbox click', async () => {
    const { container } = render(MessageCard, { msg: baseMsg, onclick: vi.fn() })
    const checkbox = container.querySelector('.msg-checkbox')
    await fireEvent.click(checkbox)
    expect(selectedIds.update).toHaveBeenCalled()
  })

  it('shows correct category pill style for alert', () => {
    render(MessageCard, { msg: { ...baseMsg, cat: 'alert' }, onclick: vi.fn() })
    const pill = screen.getByText('notice/test')
    expect(pill).toHaveStyle('color: var(--warning)')
  })

  it('shows correct category pill style for voice', () => {
    render(MessageCard, { msg: { ...baseMsg, cat: 'voice' }, onclick: vi.fn() })
    const pill = screen.getByText('notice/test')
    expect(pill).toHaveStyle('color: var(--info)')
  })
})