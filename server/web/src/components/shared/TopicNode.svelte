<script>
  import TopicNode from './TopicNode.svelte'

  export let name = ''
  export let data = {}
  export let expanded = new Set()
  export let toggle = () => {}

  const children = Object.entries(data._children || {})
  const hasChildren = children.length > 0
  const fullPath = data._path || name
</script>

<div class="topic-node">
  {#if hasChildren}
    <!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
    <div class="topic-header" onclick={() => toggle(fullPath)}>
      <svg class="chevron" class:open={expanded.has(fullPath)} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>
      <span>{name}</span>
      <span class="count">{data._count}</span>
    </div>
    {#if expanded.has(fullPath)}
      <div class="topic-children">
        {#each children as [child, cdata]}
          <TopicNode name={child} data={{...cdata, _path: fullPath + '/' + child}} {expanded} {toggle} />
        {/each}
      </div>
    {/if}
  {:else}
    <div class="topic-leaf">
      <span class="dot"></span>
      <span>{name}</span>
      <span class="count">{data._count}</span>
    </div>
  {/if}
</div>

<style>
  .topic-node { margin-bottom:2px; }
  .topic-header { display:flex; align-items:center; gap:8px; padding:8px 12px; border-radius:var(--radius-sm); cursor:pointer; font-size:13px; font-weight:500; color:var(--text-primary); }
  .topic-header:hover { background:var(--bg-hover); }
  .chevron { width:14px; height:14px; color:var(--text-hint); transition:transform .2s; flex-shrink:0; }
  .chevron.open { transform:rotate(90deg); }
  .count { margin-left:auto; font-size:10px; color:var(--text-hint); background:var(--bg-card); padding:1px 7px; border-radius:10px; flex-shrink:0; }
  .topic-children { padding-left:20px; }
  .topic-leaf { display:flex; align-items:center; gap:8px; padding:6px 12px; border-radius:var(--radius-sm); font-size:12px; color:var(--text-secondary); cursor:pointer; }
  .topic-leaf:hover { background:var(--bg-hover); color:var(--text-primary); }
  .dot { width:5px; height:5px; border-radius:50%; background:var(--text-hint); flex-shrink:0; margin-left:22px; }
</style>
