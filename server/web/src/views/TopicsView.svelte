<script>
  import { messages } from '../lib/store.js'
  import TopicNode from '../components/shared/TopicNode.svelte'

  let tree = {}
  let prevMessageCount = 0

  $: {
    if ($messages.length !== prevMessageCount || prevMessageCount === 0) {
      tree = buildTree($messages)
      const validPaths = new Set()
      collectPaths(tree, '', validPaths)
      const next = new Set()
      for (const p of expanded) {
        if (validPaths.has(p)) next.add(p)
      }
      expanded = next
      prevMessageCount = $messages.length
    }
  }

  function collectPaths(node, prefix, result) {
    for (const [name, data] of Object.entries(node)) {
      const path = prefix ? prefix + '/' + name : name
      if (data._children && Object.keys(data._children).length > 0) {
        result.add(path)
        collectPaths(data._children, path, result)
      }
    }
  }

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
    const next = new Set(expanded)
    if (next.has(path)) next.delete(path)
    else next.add(path)
    expanded = next
  }
</script>

<div class="topics-view">
  <h3>主题</h3>
  {#each Object.entries(tree) as [name, data]}
    <TopicNode {name} {data} {expanded} {toggle} />
  {/each}
</div>

<style>
  .topics-view { padding:16px 20px; overflow-y:auto; }
  .topics-view h3 { font-size:14px; font-weight:600; margin-bottom:12px; }
</style>
