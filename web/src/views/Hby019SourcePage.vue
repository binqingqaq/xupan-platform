<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const frame = ref<HTMLIFrameElement | null>(null)

function syncPlayerRoute() {
  try {
    const childLocation = frame.value?.contentWindow?.location
    const childPath = childLocation?.pathname
    const targetPath = childPath?.endsWith('/player-ui1.html')
      ? '/hby019-demo/player-ui1.html'
      : childPath?.endsWith('/player.html')
        ? '/hby019-demo/player.html'
        : undefined
    if (targetPath && targetPath !== route.path) {
      void router.push({
        path: targetPath,
        query: Object.fromEntries(new URLSearchParams(childLocation?.search || '')),
        hash: childLocation?.hash || '',
      })
    }
  } catch {
    // The source pages are same-origin; ignore non-page navigations.
  }
}

onMounted(() => document.body.classList.add('hby019-demo-host'))
onBeforeUnmount(() => document.body.classList.remove('hby019-demo-host'))

const sourceUrl = computed(() => {
  const sourcePage = route.path.endsWith('/player-ui1.html')
    ? 'player-ui1.html'
    : route.path.endsWith('/player.html')
      ? 'player.html'
      : route.path.endsWith('/console')
        ? 'console.html'
        : route.path.endsWith('/portal')
          ? 'portal.html'
          : 'index.html'

  return `/hby019-demo/source/${sourcePage}${route.fullPath.includes('?') ? route.fullPath.slice(route.fullPath.indexOf('?')) : ''}`
})
</script>

<template>
  <iframe ref="frame" class="hby019-source-page" :src="sourceUrl" :title="String(route.meta.title || 'HBY019 演示页面')" @load="syncPlayerRoute" />
</template>

<style>
html:has(body.hby019-demo-host),
body.hby019-demo-host,
body.hby019-demo-host #app {
  width: 100%;
  height: 100%;
  min-height: 100%;
  margin: 0;
  padding: 0;
  overflow: hidden;
}

.hby019-source-page {
  position: fixed;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: #fff;
}
</style>
