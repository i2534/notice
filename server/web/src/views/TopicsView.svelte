<script>
  import { messages } from '../lib/store.js'

  $: tree = buildTree($messages)

  function buildTree(msgs) {
    const root = {}
    for (const m of msgs) {
      if (!m.topic) continue
      const parts = m.topic.split('/')
      let node = root
      for (const p of parts) {
        if (!node[p]) node[p] = { _count: 0, _children: {} }
        node[p]._count++
        node = node[p]._children
      }
    }
    return root
  }

  let expanded = new Set()

  function toggle(path) {
    if (expanded.has(path)) expanded.delete(path)
    else expanded.add(path)
    expanded = expanded
  }
</script>

<div class="topics-view">
  <h3>主题</h3>
  {#each Object.entries(tree) as [name, data]}
    <div class="topic-node">
      <!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
      <div class="topic-header" onclick={() => toggle(name)}>
        <svg class="chevron" class:open={expanded.has(name)} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>
        <span>{name}</span>
        <span class="count">{data._count}</span>
      </div>
      {#if expanded.has(name)}
        <div class="topic-children">
          {#each Object.entries(data._children) as [child, cdata]}
            <div class="topic-leaf">
              <span class="dot"></span>
              <span>{child}</span>
              <span class="count">{cdata._count}</span>
            </div>
          {/each}
        </div>
      {/if}
    </div>
  {/each}
</div>

<style>
  .topics-view { padding:16px 20px; overflow-y:auto; }
  .topics-view h3 { font-size:14px; font-weight:600; margin-bottom:12px; }
  .topic-node { margin-bottom:2px; }
  .topic-header { display:flex; align-items:center; gap:8px; padding:8px 12px; border-radius:var(--radius-sm); cursor:pointer; font-size:13px; font-weight:500; color:var(--text-primary); }
  .topic-header:hover { background:var(--bg-hover); }
  .chevron { width:14px; height:14px; color:var(--text-hint); transition:transform .2s; }
  .chevron.open { transform:rotate(90deg); }
  .count { margin-left:auto; font-size:10px; color:var(--text-hint); background:var(--bg-card); padding:1px 7px; border-radius:10px; }
  .topic-children { padding-left:24px; }
  .topic-leaf { display:flex; align-items:center; gap:8px; padding:6px 12px; border-radius:var(--radius-sm); font-size:12px; color:var(--text-secondary); cursor:pointer; }
  .topic-leaf:hover { background:var(--bg-hover); color:var(--text-primary); }
  .dot { width:5px; height:5px; border-radius:50%; background:var(--text-hint); flex-shrink:0; }
</style>
