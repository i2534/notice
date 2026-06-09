<script>
  let toasts = []
  let id = 0

  export function show(message, type = 'info') {
    const tid = ++id
    toasts = [...toasts, { id: tid, message, type }]
    setTimeout(() => {
      toasts = toasts.filter(t => t.id !== tid)
    }, 2500)
  }
</script>

<div class="toast-container">
  {#each toasts as toast (toast.id)}
    <div class="toast" class:success={toast.type === 'success'} class:error={toast.type === 'error'}>
      {toast.message}
    </div>
  {/each}
</div>

<style>
  .toast-container {
    position: fixed;
    bottom: 20px; right: 20px;
    z-index: 1000;
    display: flex;
    flex-direction: column;
    gap: 8px;
    pointer-events: none;
  }
  .toast {
    padding: 10px 18px;
    border-radius: var(--radius-sm);
    background: var(--bg-card);
    border: 1px solid var(--border-light);
    color: var(--text-primary);
    font-size: 13px;
    font-weight: 500;
    box-shadow: 0 4px 20px rgba(0,0,0,.4);
    pointer-events: auto;
  }
  .toast.success { border-color: var(--accent); }
  .toast.error { border-color: var(--danger); }
</style>
