<script>
  export let message = '暂无消息'
</script>

<div class="empty-state">
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
  </svg>
  <p>{message}</p>
</div>

<style>
  .empty-state {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    color: var(--text-hint);
    gap: 12px;
    padding: 40px;
  }
  .empty-state svg { width: 48px; height: 48px; opacity: .25; }
  .empty-state p { font-size: 14px; }
</style>
