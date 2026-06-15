<script>
  import { onMount } from 'svelte'
  import { authenticated, currentRoute, settings } from './lib/store.js'
  import { authCheck } from './lib/api.js'
  import { applyTheme, getSystemTheme } from './lib/theme.js'
  import AuthView from './views/AuthView.svelte'
  import AuthenticatedLayout from './components/layout/AuthenticatedLayout.svelte'

  function navigate(path) {
    window.location.hash = '#' + path
    currentRoute.set(path)
  }

  onMount(async () => {
    if (!localStorage.getItem('noticeSettings')) {
      settings.update(s => ({ ...s, theme: getSystemTheme() }))
    }
    const s = $settings
    applyTheme(s.theme)

    // Auto-auth if token exists in settings
    // MQTT connection is handled by AuthenticatedLayout on mount
    if (s.token) {
      const res = await authCheck(s.token)
      if (res.ok) {
        authenticated.set(true)
      }
    }
  })
</script>

{#if !$authenticated}
  <AuthView />
{:else}
  <AuthenticatedLayout {navigate} />
{/if}
