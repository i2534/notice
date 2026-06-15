import { get, writable, derived } from 'svelte/store'

const STORAGE_KEY = 'noticeMessages'

const defaultSettings = {
  broker: 'ws://localhost:9092',
  topic: 'notice/#',
  token: '',
  theme: 'dark',
  maxMessages: 200,
}

function loadSettings() {
  try {
    const saved = localStorage.getItem('noticeSettings')
    return saved ? { ...defaultSettings, ...JSON.parse(saved) } : defaultSettings
  } catch { return defaultSettings }
}

// settings must be defined before saveMessages / messages (subscribe fires immediately)
export const settings = writable(loadSettings())

settings.subscribe(val => {
  try { localStorage.setItem('noticeSettings', JSON.stringify(val)) } catch (e) {
    console.warn('[store] Failed to save settings:', e)
  }
})

export function dedupByContent(list) {
  if (!list || !list.length) return list || []
  const seen = new Set()
  return list.filter(m => {
    const key = `${m.title}|${m.content}|${new Date(m.timestamp).toISOString().slice(0, 16)}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/**
 * 按 ID 合并消息列表（历史 + 实时），按时间降序排列
 */
export function mergeMessages(existing = [], incoming = []) {
  const map = new Map(existing.map(m => [m.id, m]))
  for (const m of incoming) {
    if (!map.has(m.id)) map.set(m.id, m)
  }
  const merged = [...map.values()]
  merged.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())
  return merged
}

export function loadCachedMessages() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const msgs = JSON.parse(raw)
    if (!Array.isArray(msgs)) return []
    return dedupByContent(msgs)
  } catch { return [] }
}

export function saveMessages(list) {
  try {
    const max = get(settings).maxMessages || 200
    const deduped = dedupByContent(list)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(deduped.slice(-max)))
  } catch (e) {
    console.warn('[store] Failed to persist messages:', e)
  }
}

export const messages = writable(loadCachedMessages())


let saveTimer = null
messages.subscribe(val => {
  clearTimeout(saveTimer)
  saveTimer = setTimeout(() => saveMessages(val), 1000)
})

function getRouteFromHash() {
  if (typeof window === 'undefined') return '/messages'

  const hash = window.location.hash || '#/messages'
  const path = hash.startsWith('#') ? hash.slice(1) : hash
  return path === '/topics' || path === '/clients' || path === '/messages' ? path : '/messages'
}

export const selectedIds = writable(new Set())
export const currentView = writable('messages')
export const currentRoute = writable(getRouteFromHash())

if (typeof window !== 'undefined') {
  window.addEventListener('hashchange', () => currentRoute.set(getRouteFromHash()))
}

function createDebouncedWritable(initial = '', delay = 200) {
  const store = writable(initial)
  let timer = null
  return {
    subscribe: store.subscribe,
    set(val) {
      clearTimeout(timer)
      timer = setTimeout(() => store.set(val), delay)
    },
    update(fn) {
      clearTimeout(timer)
      timer = setTimeout(() => store.update(fn), delay)
    },
    flush(val) { clearTimeout(timer); store.set(val) },
  }
}
export const searchQuery = createDebouncedWritable('', 200)
export const currentFilter = writable('all')
export const connectionStatus = writable('disconnected')
export const authenticated = writable(false)
export const mqttClient = writable(null)
export const detailMessageId = writable(null)
export const sendPanelOpen = writable(false)
export const settingsPanelOpen = writable(false)

export const filteredMessages = derived(
  [messages, searchQuery, currentFilter],
  ([$messages, $searchQuery, $currentFilter]) => {
    let result = $messages
    if ($searchQuery.trim()) {
      const q = $searchQuery.trim().toLowerCase()
      result = result.filter(m =>
        m.title?.toLowerCase().includes(q) ||
        m.content?.toLowerCase().includes(q) ||
        m.topic?.toLowerCase().includes(q) ||
        m.client?.toLowerCase().includes(q)
      )
    }
    if ($currentFilter === 'unread') result = result.filter(m => m.unread)
    else if ($currentFilter === 'today') result = result.filter(m => m.timestamp && new Date(m.timestamp).toDateString() === new Date().toDateString())
    else if ($currentFilter !== 'all') result = result.filter(m => m.cat === $currentFilter)
    // 按时间降序排列（最新在前）
    result = [...result].sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp))
    return result
  }
)
