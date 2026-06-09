<script>
  import { afterUpdate } from 'svelte'
  import { filteredMessages, detailMessageId } from '../../lib/store.js'
  import MessageCard from './MessageCard.svelte'
  import EmptyState from '../shared/EmptyState.svelte'

  let listEl

  // Auto scroll to bottom when new messages arrive
  afterUpdate(() => {
    if (listEl) listEl.scrollTop = listEl.scrollHeight
  })
</script>

<div class="message-list" bind:this={listEl}>
  {#if $filteredMessages.length === 0}
    <EmptyState message="暂无消息" />
  {:else}
    {#each $filteredMessages as msg (msg.id)}
      <MessageCard {msg} onclick={() => detailMessageId.set(msg.id)} />
    {/each}
  {/if}
</div>

<style>
  .message-list { flex:1; overflow-y:auto; padding:12px 16px; display:flex; flex-direction:column; gap:6px; }
</style>
