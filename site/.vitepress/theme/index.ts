import DefaultTheme from 'vitepress/theme'
import { h } from 'vue'
import VersionMenu from './VersionMenu.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  Layout: () => h(DefaultTheme.Layout, null, { 'nav-bar-content-after': () => h(VersionMenu) }),
}
