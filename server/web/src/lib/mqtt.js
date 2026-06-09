import mqtt from 'mqtt'
import { get } from 'svelte/store'
import { messages, connectionStatus, mqttClient, settings } from './store.js'
import { normalizeMessagePayload, decodeNoticeMqttPayloadIfEncoded, topicForPublish } from './utils.js'

let client = null
let lastSentContent = ''
let lastSentTime = 0

export function setLastSent(content) {
  lastSentContent = content
  lastSentTime = Date.now()
}

export function connect(brokerUrl, topic, token) {
  if (client) { client.end(true); client = null }

  connectionStatus.set('connecting')
  const clientId = 'web-' + Math.random().toString(16).slice(2, 10)

  client = mqtt.connect(brokerUrl, {
    clientId,
    username: token,
    password: token,
    reconnectPeriod: 3000,
    clean: true,
  })

  client.on('connect', () => {
    connectionStatus.set('connected')
    client.subscribe(topic, (err) => {
      if (err) console.warn('[mqtt] subscribe error', err)
    })
  })

  client.on('message', async (rawTopic, payload) => {
    const normTopic = topicForPublish(rawTopic)
    let msg
    try { msg = JSON.parse(payload.toString()) }
    catch { msg = { content: payload.toString() } }

    if (msg.content === '__auth_check__') return
    msg = await decodeNoticeMqttPayloadIfEncoded(msg)
    msg = normalizeMessagePayload(msg)
    const content = (msg.content ?? '').toString().trim()

    if (lastSentContent && content === lastSentContent && (Date.now() - lastSentTime) < 5000) {
      lastSentContent = ''
      return
    }

    const newMsg = {
      id: Date.now() + Math.random(),
      topic: normTopic,
      title: msg.title || '通知',
      content,
      timestamp: msg.timestamp || new Date().toISOString(),
      client: msg.client || '',
      unread: true,
      cat: normTopic.includes('alert') ? 'alert' : normTopic.includes('voice') ? 'voice' : 'system',
    }

    messages.update(list => {
      // Dedup: check if same content already in last 5 seconds
      const last = list[list.length - 1]
      if (last && last.title === newMsg.title && last.content === newMsg.content && Math.abs(new Date(last.timestamp) - new Date(newMsg.timestamp)) < 5000) {
        return list
      }
      // Desktop notification
      if ('Notification' in window && Notification.permission === 'granted') {
        try { new Notification(newMsg.title, { body: newMsg.content }) } catch (e) { /* ignore */ }
      }
      const max = get(settings).maxMessages || 200
      return [...list, newMsg].slice(-max)
    })
  })

  client.on('reconnect', () => {
    connectionStatus.set('connecting')
  })

  client.on('error', (err) => {
    const m = (err.message || '').toLowerCase()
    if (m.includes('not authorized') || m.includes('bad user') || m.includes('auth')) {
      connectionStatus.set('disconnected')
    } else {
      console.warn('[mqtt] error:', err.message)
    }
  })

  client.on('close', () => { connectionStatus.set('disconnected') })
  mqttClient.set(client)
}

export function disconnect() {
  if (client) { client.end(true); client = null }
  connectionStatus.set('disconnected')
  mqttClient.set(null)
}

// Request notification permission on load
if ('Notification' in window && Notification.permission === 'default') {
  Notification.requestPermission()
}
