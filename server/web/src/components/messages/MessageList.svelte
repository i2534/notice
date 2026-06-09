<script>
  import { afterUpdate } from 'svelte'
  import { filteredMessages, detailMessageId, messages } from '../../lib/store.js'
  import MessageCard from './MessageCard.svelte'
  import EmptyState from '../shared/EmptyState.svelte'

  let listEl

  // Auto scroll to bottom only when already near bottom
  afterUpdate(() => {
    if (!listEl) return
    const { scrollTop, scrollHeight, clientHeight } = listEl
    if (scrollHeight - scrollTop - clientHeight < 100) {
      listEl.scrollTop = scrollHeight
    }
  })
</script>

<div class="message-list" bind:this={listEl}>
  {#if $filteredMessages.length === 0}
    <EmptyState message="暂无消息" />
  {:else}
    {#each $filteredMessages as msg (msg.id)}
      <MessageCard {msg} onclick={() => {
        detailMessageId.set(msg.id)
        if (msg.unread) messages.update(list => list.map(m => m.id === msg.id ? { ...m, unread: false } : m))
      }} />
    {/each}
  {/if}
</div>

<style>
  .message-list { flex:1; overflow-y:auto; padding:12px 16px; display:flex; flex-direction:column; gap:6px; }
</style>
