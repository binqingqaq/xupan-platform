import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [vue(), Components({ dirs: [], dts: false, resolvers: [ElementPlusResolver({ importStyle: false })] })],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/testSetup.ts'],
    include: ['src/**/*.test.ts'],
    restoreMocks: true,
    clearMocks: true,
  },
})
