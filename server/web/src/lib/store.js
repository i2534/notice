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

function loadCachedMessages() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : []
  } catch { return [] }
}

function saveMessages(list) {
  try {
    const max = get(settings).maxMessages || 200
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list.slice(-max)))
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
    return result
  }
)
