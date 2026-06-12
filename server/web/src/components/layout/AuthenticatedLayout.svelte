<script>
  import { onMount } from 'svelte'
  import { currentView, settings, settingsPanelOpen, detailMessageId, messages } from '../../lib/store.js'
import { connect } from '../../lib/mqtt.js'
import { applyTheme } from '../../lib/theme.js'
import { fetchMessages } from '../../lib/api.js'
import { normalizeMessagePayload } from '../../lib/utils.js'
  import Sidebar from './Sidebar.svelte'
  import Topbar from './Topbar.svelte'
  import MessagesView from '../../views/MessagesView.svelte'
  import TopicsView from '../../views/TopicsView.svelte'
  import ClientsView from '../../views/ClientsView.svelte'
  import SettingsPanel from '../settings/SettingsPanel.svelte'
  import MessageDetail from '../messages/MessageDetail.svelte'
  import Toast from '../shared/Toast.svelte'
  import ConfirmDialog from '../shared/ConfirmDialog.svelte'

  let toastRef
  let confirmRef

  function showToast(msg, type) { toastRef?.show(msg, type) }
  function showConfirm(msg, cb) { confirmRef?.confirm(msg, cb) }

  onMount(async () => {
    const s = $settings
    applyTheme(s.theme)

    // Fetch historical messages from server before connecting MQTT
    const res = await fetchMessages(s.token, 50)
    if (res.ok && Array.isArray(res.data?.data?.messages)) {
      const history = res.data.data.messages.map(m => {
        const normalized = normalizeMessagePayload({
          id: m.id,
          topic: m.topic || 'notice',
          title: m.title || '通知',
          content: m.content || '',
          timestamp: m.timestamp || new Date().toISOString(),
          client: m.client || '',
          unread: false,
        })
        return {
          ...normalized,
          cat: (normalized.topic || '').includes('alert') ? 'alert' : (normalized.topic || '').includes('voice') ? 'voice' : 'system',
        }
      })
      messages.update(list => {
        const existing = new Map(list.map(m => [m.id, m]))
        // 内容+时间窗口去重：已通过 MQTT 收到的消息不再从 API 重复添加（MQTT 用随机 ID，API 用整数 ID）
        const contentSeen = new Set(list.map(m => `${m.title}|${m.content}|${new Date(m.timestamp).toISOString().slice(0, 16)}`))
        for (const m of history) {
          if (existing.has(m.id)) continue
          const key = `${m.title}|${m.content}|${new Date(m.timestamp).toISOString().slice(0, 16)}`
          if (contentSeen.has(key)) continue
          contentSeen.add(key)
          existing.set(m.id, m)
        }
        const max = $settings.maxMessages || 200
        const merged = [...existing.values()]
        merged.sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp))
        return merged.slice(0, max)
      })
    }

    if (s.broker && s.token) {
      connect(s.broker, s.topic, s.token)
    }
  })
</script>

<svelte:window onkeydown={(e) => {
  if (e.key === 'Escape') {
    settingsPanelOpen.set(false)
    detailMessageId.set(null)
    // 关于弹窗在 Topbar 内部自行处理
  }
}} />

<div class="app-layout">
  <Sidebar {showToast} />
  <div class="main-area">
    <Topbar {showToast} />
    <div class="view-container">
      {#if $currentView === 'messages'}
        <MessagesView {showToast} {showConfirm} />
      {:else if $currentView === 'topics'}
        <TopicsView />
      {:else if $currentView === 'clients'}
        <ClientsView />
      {/if}
    </div>
  </div>
  {#if $settingsPanelOpen}
    <SettingsPanel />
  {/if}
  {#if $detailMessageId != null}
    <MessageDetail {showToast} {showConfirm} />
  {/if}
  <Toast bind:this={toastRef} />
  <ConfirmDialog bind:this={confirmRef} />
</div>

<style>
  .app-layout {
    flex: 1;
    display: flex;
    height: 100vh;
    overflow: hidden;
  }
  .main-area {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-width: 0;
    background: var(--bg-surface);
  }
  .view-container {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    background: var(--bg-deep);
  }
</style>
