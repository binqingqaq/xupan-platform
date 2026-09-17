<script setup lang="ts">
import type { RobotDrawPayload } from '../robotDrawMessage'
import { computed } from 'vue'
import { renderRobotDrawImage } from '../robotDrawImage'

const props = defineProps<{ payload: RobotDrawPayload }>()
const image = computed(() => renderRobotDrawImage(props.payload))
</script>

<template>
  <div class="robot-draw-image-wrap" role="img" :aria-label="image.alt" @contextmenu.prevent>
    <img class="robot-draw-image" :src="image.dataUrl" :width="image.width" :height="image.height" alt="" draggable="false" @dragstart.prevent />
  </div>
</template>
