<script>
  import { onMount, onDestroy } from 'svelte'
  import { currentRoute, settings, settingsPanelOpen, detailMessageId, messages, mergeMessages } from '../../lib/store.js'
  import { connect } from '../../lib/mqtt.js'
  import { applyTheme } from '../../lib/theme.js'
  import { fetchMessages } from '../../lib/api.js'
  import { normalizeAndCategorize } from '../../lib/utils.js'
  import Sidebar from './Sidebar.svelte'
  import Topbar from './Topbar.svelte'
  import MessagesView from '../../views/MessagesView.svelte'
  import TopicsView from '../../views/TopicsView.svelte'
  import ClientsView from '../../views/ClientsView.svelte'
  import SettingsPanel from '../settings/SettingsPanel.svelte'
  import MessageDetail from '../messages/MessageDetail.svelte'
  import Toast from '../shared/Toast.svelte'
  import ConfirmDialog from '../shared/ConfirmDialog.svelte'

  export let navigate = () => {}

  let toastRef
  let confirmRef
  let mobileMenuOpen = false

  $: view = $currentRoute === '/topics' ? 'topics' : $currentRoute === '/clients' ? 'clients' : 'messages'

  function showToast(msg, type) { toastRef?.show(msg, type) }
  function showConfirm(msg, cb) { confirmRef?.confirm(msg, cb) }
  function toggleMobileMenu() { mobileMenuOpen = !mobileMenuOpen }

  // Polling state
  let pollInterval = null
  const POLL_INTERVAL_MS = 30000 // 30 seconds
  let isPolling = false
  let isPageVisible = true

  // 轮询拉取最新 50 条（不带 before_id 游标——游标语义是"取更旧的"，
  // 永远拉不到新消息），按服务端 id 合并，作为 MQTT 断连时漏收消息的兜底
  async function pollMessages() {
    if (isPolling || !isPageVisible) return
    const s = $settings
    if (!s.token) return

    isPolling = true
    try {
      const res = await fetchMessages(s.token, 50)
      if (res.ok && Array.isArray(res.data?.data?.messages) && res.data.data.messages.length > 0) {
        const latest = res.data.data.messages.map(m =>
          normalizeAndCategorize({
            id: m.id,
            topic: m.topic,
            title: m.title,
            content: m.content,
            timestamp: m.timestamp,
            client: m.client,
            unread: false,
          })
        )
        messages.update(list => {
          const max = $settings.maxMessages || 200
          return mergeMessages(list, latest).slice(0, max)
        })
      }
    } catch (e) {
      console.warn('[poll] Failed to fetch messages:', e)
    } finally {
      isPolling = false
    }
  }

  // Start/stop polling
  function startPolling() {
    if (pollInterval) return
    pollInterval = setInterval(pollMessages, POLL_INTERVAL_MS)
  }

  function stopPolling() {
    if (pollInterval) {
      clearInterval(pollInterval)
      pollInterval = null
    }
  }

  // Visibility change handler
  function handleVisibilityChange() {
    isPageVisible = !document.hidden
    if (isPageVisible) {
      // Page became visible, do an immediate poll
      pollMessages()
    }
  }

  onMount(async () => {
    const s = $settings
    applyTheme(s.theme)

    // Fetch historical messages from server before connecting MQTT
    const res = await fetchMessages(s.token, 50)
    if (res.ok && Array.isArray(res.data?.data?.messages)) {
      const history = res.data.data.messages.map(m =>
        normalizeAndCategorize({
          id: m.id,
          topic: m.topic,
          title: m.title,
          content: m.content,
          timestamp: m.timestamp,
          client: m.client,
          unread: false,
        })
      )
      messages.update(list => {
        const max = $settings.maxMessages || 200
        return mergeMessages(list, history).slice(0, max)
      })
    }

    if (s.broker && s.token) {
      connect(s.broker, s.topic, s.token)
    }

    // Start polling for message sync (MQTT fallback)
    startPolling()
    document.addEventListener('visibilitychange', handleVisibilityChange)
  })

  onDestroy(() => {
    stopPolling()
    document.removeEventListener('visibilitychange', handleVisibilityChange)
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
  <Sidebar {showToast} {navigate} mobileOpen={mobileMenuOpen} onClose={() => mobileMenuOpen = false} />
  <div class="main-area">
    <Topbar {showToast} onMenuToggle={toggleMobileMenu} />
    <div class="view-container">
      {#if view === 'messages'}
        <MessagesView {showToast} {showConfirm} />
      {:else if view === 'topics'}
        <TopicsView />
      {:else if view === 'clients'}
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
