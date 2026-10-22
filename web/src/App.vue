<script setup lang="ts">
import { useRoute } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

const route = useRoute()
</script>

<template>
  <el-config-provider v-if="route.path.startsWith('/platform-admin')" :locale="zhCn" size="default">
    <RouterView />
  </el-config-provider>
  <RouterView v-else />
</template>
