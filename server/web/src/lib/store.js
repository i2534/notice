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

// Persist messages to localStorage on every change
messages.subscribe(val => saveMessages(val))

export const selectedIds = writable(new Set())
export const currentView = writable('messages')
export const searchQuery = writable('')
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
