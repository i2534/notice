<script>
  import { onDestroy } from 'svelte'

  let toasts = []
  let id = 0
  let timers = []

  export function show(message, type = 'info') {
    const tid = ++id
    toasts = [...toasts, { id: tid, message, type }]
    const timer = setTimeout(() => {
      toasts = toasts.filter(t => t.id !== tid)
      timers = timers.filter(t => t !== timer)
    }, 2500)
    timers.push(timer)
  }

  onDestroy(() => {
    timers.forEach(clearTimeout)
  })
</script>

<div class="toast-container" role="status" aria-live="polite">
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
    z-index: var(--z-toast);
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
    box-shadow: var(--shadow);
    pointer-events: auto;
  }
  .toast.success { border-color: var(--accent); }
  .toast.error { border-color: var(--danger); }
</style>
