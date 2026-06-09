<script>
  import { onMount } from 'svelte'
  import { currentView, settings, settingsPanelOpen, detailMessageId } from '../../lib/store.js'
  import { connect } from '../../lib/mqtt.js'
  import { applyTheme } from '../../lib/theme.js'
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

  onMount(() => {
    const s = $settings
    applyTheme(s.theme)
    if (s.broker && s.token) {
      connect(s.broker, s.topic, s.token)
    }
  })
</script>

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
