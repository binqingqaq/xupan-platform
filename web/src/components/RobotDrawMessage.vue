<script setup lang="ts">
import type { RobotDrawPayload } from '../robotDrawMessage'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { renderRobotDrawImage } from '../robotDrawImage'

const props = defineProps<{ payload: RobotDrawPayload }>()
const container = ref<HTMLDivElement | null>(null)
const availableWidth = ref<number>()
let resizeObserver: ResizeObserver | null = null
let resizeHandler: (() => void) | null = null
const image = computed(() => renderRobotDrawImage(props.payload, availableWidth.value))

onMounted(() => {
  const updateWidth = () => {
    const width = container.value?.clientWidth ?? 0
    if (width > 0) availableWidth.value = width
  }
  updateWidth()
  if (typeof ResizeObserver === 'undefined') {
    resizeHandler = updateWidth
    window.addEventListener('resize', resizeHandler)
    return
  }
  resizeObserver = new ResizeObserver(updateWidth)
  if (container.value) resizeObserver.observe(container.value)
})

onUnmounted(() => {
  resizeObserver?.disconnect()
  if (resizeHandler) window.removeEventListener('resize', resizeHandler)
})
</script>

<template>
  <div ref="container" class="robot-draw-image-wrap" role="img" :aria-label="image.alt" @contextmenu.prevent>
    <img class="robot-draw-image" :src="image.dataUrl" :width="image.width" :height="image.height" alt="" draggable="false" @dragstart.prevent />
  </div>
</template>
