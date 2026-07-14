import mqtt from 'mqtt'
import { get } from 'svelte/store'
import { messages, connectionStatus, mqttClient, settings } from './store.js'
import { normalizeMessagePayload, decodeNoticeMqttPayloadIfEncoded, topicForPublish, normalizeAndCategorize } from './utils.js'

/** @type {import('mqtt').MqttClient | null} */
let client = null

/**
 * @param {string} brokerUrl
 * @param {string} topic
 * @param {string} token
 */
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
      if (err) {
        console.error('[mqtt] 订阅失败，请检查主题配置:', err.message)
        connectionStatus.set('error')
      }
    })
  })

  client.on('message', async (rawTopic, payload) => {
    const normTopic = topicForPublish(rawTopic)
    let msg
    try { msg = JSON.parse(payload.toString()) }
    catch { msg = { content: payload.toString() } }

    if (msg.content === '__auth_check__') return
    const serverId = msg.id
    msg = await decodeNoticeMqttPayloadIfEncoded(msg)
    msg = normalizeMessagePayload(msg)

    const newMsg = normalizeAndCategorize({
      id: serverId != null && serverId !== '' ? serverId : (msg.id != null && msg.id !== '' ? msg.id : crypto.randomUUID()),
      topic: normTopic,
      title: msg.title,
      content: (msg.content ?? '').toString().trim(),
      timestamp: msg.timestamp,
      client: msg.client,
      unread: true,
    })

    messages.update(list => {
      // 优先按服务端 id 去重（与 HTTP 历史同源）
      if (list.some(m => String(m.id) === String(newMsg.id))) return list
      // 兼容旧消息：滑动窗口内容去重
      const recent = list.slice(-20).filter(m =>
        m.title === newMsg.title &&
        m.content === newMsg.content &&
        Math.abs(new Date(m.timestamp).getTime() - new Date(newMsg.timestamp).getTime()) < 5000
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
