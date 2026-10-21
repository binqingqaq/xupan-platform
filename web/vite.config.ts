import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { resolve } from 'node:path'

export default defineConfig({
  plugins: [
    vue(),
    Components({
      dirs: [],
      resolvers: [ElementPlusResolver({ importStyle: 'css' })],
      dts: resolve(__dirname, 'src/components.d.ts'),
    }),
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/ws': {
        target: 'http://127.0.0.1:8080',
        ws: true,
      },
    },
  },
  build: {
    outDir: resolve(__dirname, '../server/xupan-server/src/main/resources/static'),
    emptyOutDir: true,
  },
})
