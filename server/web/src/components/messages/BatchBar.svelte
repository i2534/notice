<script>
  import { selectedIds, messages } from '../../lib/store.js'

  export let showToast = () => {}
  export let showConfirm = () => {}

  $: count = $selectedIds.size

  function clearSelection() { selectedIds.set(new Set()) }
  function deleteSelected() {
    const idsSnapshot = new Set($selectedIds)
    const countSnapshot = idsSnapshot.size
    if (countSnapshot === 0) return
    showConfirm(`确定要删除选中的 ${countSnapshot} 条消息吗？`, () => {
      messages.update(list => list.filter(m => !idsSnapshot.has(m.id)))
      selectedIds.set(new Set())
      showToast(`已删除 ${countSnapshot} 条消息`, 'success')
    }, { variant: 'danger' })
  }
</script>

{#if count > 0}
  <div class="batch-bar">
    <span class="batch-count">已选 {count} 条</span>
    <div class="batch-actions">
      <button class="btn-ghost-sm" onclick={clearSelection}>取消选择</button>
      <button class="btn-danger-sm" onclick={deleteSelected}>删除选中</button>
    </div>
  </div>
{/if}

<style>
  .batch-bar { display:flex; align-items:center; gap:12px; padding:8px 20px; background:var(--accent-alpha-15); border-bottom:1px solid var(--accent); flex-shrink:0; }
  .batch-count { font-size:13px; font-weight:600; color:var(--accent); }
  .batch-actions { margin-left:auto; display:flex; gap:8px; }
  .btn-ghost-sm { background:none; border:none; color:var(--text-hint); font-size:12px; cursor:pointer; font-family:inherit; padding:4px 8px; border-radius:var(--radius-sm); }
  .btn-ghost-sm:hover { color:var(--text-primary); background:var(--bg-hover); }
  .btn-danger-sm { background:none; border:1px solid var(--danger); color:var(--danger); font-size:12px; cursor:pointer; font-family:inherit; padding:4px 12px; border-radius:var(--radius-sm); }
  .btn-danger-sm:hover { background:var(--danger-dim); }
</style>
