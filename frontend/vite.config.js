import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    Components({
      resolvers: [ElementPlusResolver()]
    })
  ],
  server: {
    port: 5173,
    proxy: {
      '/api/ai/recipes/generate/stream': {
        target: 'http://localhost:7068',
        timeout: 120000,
        proxyTimeout: 120000
      },
      '/api': {
        target: 'http://localhost:7068',
        timeout: 180000,
        proxyTimeout: 180000
      }
    }
  },
  build: {
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      onwarn(warning, warn) {
        if (warning.code === 'INVALID_ANNOTATION' && warning.id?.includes('@vueuse/core')) {
          return
        }

        warn(warning)
      },
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia']
        }
      }
    }
  }
})
