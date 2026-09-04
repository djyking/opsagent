<script setup lang="ts">
import { computed, ref } from "vue";
import { Check, Copy } from "@lucide/vue";

const props = withDefaults(defineProps<{ metadata: Record<string, string | number | boolean>; previewCount?: number }>(), { previewCount: 4 });
const expanded = ref(false);
const copiedKey = ref("");
const entries = computed(() => Object.entries(props.metadata));
const visibleEntries = computed(() => entries.value.slice(0, props.previewCount));
const hiddenCount = computed(() => Math.max(0, entries.value.length - props.previewCount));
const rawJson = computed(() => JSON.stringify(props.metadata, null, 2));

async function copyValue(key: string, value: string | number | boolean) {
  await navigator.clipboard.writeText(String(value));
  copiedKey.value = key;
  window.setTimeout(() => { if (copiedKey.value === key) copiedKey.value = ""; }, 1200);
}
</script>

<template>
  <section v-if="entries.length" class="technical-metadata">
    <header>
      <div><strong>技术标签</strong><span>{{ entries.length }}</span></div>
      <button type="button" class="text-button" @click="expanded = !expanded">{{ expanded ? "收起" : "展开技术标签" }}</button>
    </header>
    <div v-if="!expanded" class="technical-chip-list">
      <span v-for="([key, value]) in visibleEntries" :key="key" class="technical-chip">
        <span class="technical-chip__key">{{ key }}:</span><code class="technical-chip__value" :title="String(value)">{{ value }}</code>
      </span>
      <button v-if="hiddenCount" type="button" class="technical-chip technical-chip-more" @click="expanded = true">+{{ hiddenCount }}</button>
    </div>
    <template v-else>
      <dl class="technical-metadata-grid">
        <div v-for="([key, value]) in entries" :key="key">
          <dt>{{ key }}</dt><dd><code>{{ value }}</code><button type="button" class="icon-button" :title="'复制 ' + key" @click="copyValue(key, value)"><Check v-if="copiedKey === key" :size="16" /><Copy v-else :size="16" /></button></dd>
        </div>
      </dl>
      <details class="technical-raw"><summary>查看原始数据</summary><pre>{{ rawJson }}</pre></details>
    </template>
  </section>
</template>