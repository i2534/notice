<script>
  import { onMount } from 'svelte'
  import { authenticated, settings } from './lib/store.js'
  import { authCheck } from './lib/api.js'
  import { connect } from './lib/mqtt.js'
  import { applyTheme } from './lib/theme.js'
  import AuthView from './views/AuthView.svelte'
  import AuthenticatedLayout from './components/layout/AuthenticatedLayout.svelte'

  onMount(async () => {
    const s = $settings
    applyTheme(s.theme)

    // Auto-auth if token exists in settings
    if (s.token) {
      const res = await authCheck(s.token)
      if (res.ok) {
        authenticated.set(true)
        if (s.broker) connect(s.broker, s.topic, s.token)
      }
    }
  })
</script>

{#if !$authenticated}
  <AuthView />
{:else}
  <AuthenticatedLayout />
{/if}
