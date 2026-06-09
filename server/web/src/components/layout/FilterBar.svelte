<script>
  import { currentFilter, filteredMessages, selectedIds } from '../../lib/store.js'

  const filters = [
    { id: 'all', label: '全部' },
    { id: 'unread', label: '未读' },
    { id: 'today', label: '今天' },
    { id: 'system', label: '通知' },
    { id: 'alert', label: '警告' },
    { id: 'voice', label: '语音' },
  ]

  $: count = $filteredMessages.length

  function toggleSelectAll() {
    const ids = $filteredMessages.map(m => m.id)
    const allSelected = ids.every(id => $selectedIds.has(id))
    if (allSelected) ids.forEach(id => $selectedIds.delete(id))
    else ids.forEach(id => $selectedIds.add(id))
    selectedIds.set($selectedIds)
  }
</script>

<div class="filter-bar">
  {#each filters as f}
    <button
      class="filter-pill"
      class:active={$currentFilter === f.id}
      onclick={() => currentFilter.set(f.id)}
    >{f.label}</button>
  {/each}
  <div class="spacer"></div>
  <span class="meta-text">{count} 条消息</span>
  <button class="btn-icon-sm" onclick={toggleSelectAll}>全选</button>
</div>

<style>
  .filter-bar {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 20px;
    border-bottom: 1px solid var(--border);
    flex-shrink: 0;
    background: var(--bg-surface);
  }
  .filter-pill {
    padding: 4px 12px;
    border-radius: 20px;
    border: 1px solid var(--border);
    background: transparent;
    color: var(--text-hint);
    font-size: 12px;
    font-weight: 500;
    cursor: pointer;
    transition: all .15s;
    font-family: inherit;
  }
  .filter-pill:hover { border-color: var(--border-light); color: var(--text-secondary); }
  .filter-pill.active { background: var(--accent-dim); border-color: var(--accent); color: var(--accent); }
  .spacer { flex: 1; }
  .meta-text { font-size: 11px; color: var(--text-hint); font-weight: 500; }
  .btn-icon-sm {
    background: none;
    border: none;
    color: var(--text-hint);
    font-size: 12px;
    cursor: pointer;
    font-family: inherit;
    padding: 4px 8px;
    border-radius: 4px;
  }
  .btn-icon-sm:hover { color: var(--text-primary); background: var(--bg-hover); }
</style>
