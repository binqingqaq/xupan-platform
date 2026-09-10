import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import UserRoom from './views/UserRoom.vue'
import AdminPanel from './views/AdminPanel.vue'
import './styles.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/room' },
    { path: '/room', component: UserRoom },
    { path: '/admin', component: AdminPanel },
  ],
})

createApp(App).use(router).mount('#app')
