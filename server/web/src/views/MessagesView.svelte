<script>
  import { sendPanelOpen } from '../lib/store.js'
  import FilterBar from '../components/layout/FilterBar.svelte'
  import MessageList from '../components/messages/MessageList.svelte'
  import BatchBar from '../components/messages/BatchBar.svelte'
  import SendFab from '../components/send/SendFab.svelte'
  import SendPanel from '../components/send/SendPanel.svelte'

  export let showToast = () => {}
  export let showConfirm = () => {}
</script>

<div class="messages-view">
  <FilterBar />
  <BatchBar {showToast} {showConfirm} />
  <MessageList />
  {#if $sendPanelOpen}
    <SendPanel {showToast} />
  {:else}
    <SendFab />
  {/if}
</div>

<style>
  .messages-view { flex:1; display:flex; flex-direction:column; overflow:hidden; position:relative; }
</style>
