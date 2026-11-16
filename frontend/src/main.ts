import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/theme.css'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, {
  locale: zhCn,
})

// 等路由解析完首帧再挂载：否则首个渲染周期的路由是 START_LOCATION（meta 为空），
// App.vue 的 isRouteBare 算出来是 false，会先渲染一帧 MainLayout 再被替换。
// PC 上只闪一下，移动端在 SSO 回跳时会明显看到侧边栏闪出（2026-11-16 排查）。
router.isReady().then(() => app.mount('#app'))
