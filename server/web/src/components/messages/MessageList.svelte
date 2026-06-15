<script>
  import { filteredMessages, detailMessageId, messages } from '../../lib/store.js'
  import MessageCard from './MessageCard.svelte'
  import EmptyState from '../shared/EmptyState.svelte'

  let listEl

  // Auto scroll to top when already near top（最新在前，新消息出现在顶部）
  $effect(() => {
    $filteredMessages  // track dependency
    if (!listEl) return
    if (listEl.scrollTop < 100) {
      listEl.scrollTop = 0
    }
  })
</script>

<div class="message-list" bind:this={listEl} tabindex="0">
  {#if $filteredMessages.length === 0}
    <EmptyState message="暂无消息" />
  {:else}
    {#each $filteredMessages as msg (msg.id)}
      <MessageCard {msg} onclick={() => {
        detailMessageId.set(msg.id)
        if (msg.unread) setTimeout(() => {
          messages.update(list => list.map(m => m.id === msg.id ? { ...m, unread: false } : m))
        }, 0)
      }} />
    {/each}
  {/if}
</div>

<style>
  .message-list { flex:1; overflow-y:auto; padding:12px 16px; display:flex; flex-direction:column; gap:6px; }
</style>
