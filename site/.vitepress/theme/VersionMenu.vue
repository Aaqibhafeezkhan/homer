<script setup lang="ts">
// Lists the published versions from versions.json, which the Pages deployment writes one level
// above every version's base (e.g. /homer/versions.json for /homer/1.34.0/). A local build has no
// such file, so the menu stays hidden.
import { onMounted, ref } from 'vue'
import { useData } from 'vitepress'
import { menuEntries, versionsRoot } from './versions.mjs'

const { site } = useData()
const versions = ref<{ name: string; path: string }[]>([])
const current = ref('')

onMounted(async () => {
  current.value = document.querySelector('meta[name="homer-docs-version"]')?.getAttribute('content') ?? ''
  try {
    const root = versionsRoot(site.value.base, location.origin)
    const res = await fetch(new URL('versions.json', root))
    if (!res.ok) return
    versions.value = menuEntries(await res.json(), root)
  } catch {
    // No menu without the list.
  }
})

function go(event: Event) {
  const path = (event.target as HTMLSelectElement).value
  if (path) location.assign(path)
}
</script>

<template>
  <label v-if="versions.length > 0" class="version-menu">
    <span class="visually-hidden">Documentation version</span>
    <select :value="''" @change="go">
      <option value="" disabled>{{ current }}</option>
      <option v-for="v in versions" :key="v.name" :value="v.path">{{ v.name }}</option>
    </select>
  </label>
</template>

<style scoped>
.version-menu select {
  margin-left: 12px;
  padding: 2px 6px;
  border: 1px solid var(--vp-c-divider);
  border-radius: 6px;
  background: var(--vp-c-bg);
  color: var(--vp-c-text-1);
  font-size: 13px;
}
.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
</style>
