import mqtt from 'mqtt'
import { get } from 'svelte/store'
import { messages, connectionStatus, mqttClient, settings } from './store.js'
import { normalizeMessagePayload, decodeNoticeMqttPayloadIfEncoded, topicForPublish } from './utils.js'

let client = null

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
      // Dedup: check if same content already in last 5 seconds (滑动窗口，不依赖位置)
      const recent = list.slice(-20).filter(m =>
        m.title === newMsg.title &&
        m.content === newMsg.content &&
        Math.abs(new Date(m.timestamp) - new Date(newMsg.timestamp)) < 5000
      )
      if (recent.length > 0) return list
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
