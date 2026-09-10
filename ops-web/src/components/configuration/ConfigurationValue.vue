<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Copy } from '@lucide/vue';
const props = defineProps<{ value: unknown; type?: string; label: string; sensitive?: boolean; hasValue?: boolean }>();
const copyState = ref('');
const text = computed(() => props.sensitive ? props.hasValue ? '已设置' : '未设置'
  : props.value == null ? '未设置' : props.type === 'boolean' ? props.value ? '开启' : '关闭'
    : typeof props.value === 'object' ? JSON.stringify(props.value, null, 2) : String(props.value));
const expandedValue = computed(() => !props.sensitive && props.value != null
  && (props.type === 'json' || typeof props.value === 'object' || text.value.length > 180 || text.value.split('\n').length > 3));
const summary = computed(() => Array.isArray(props.value) ? `数组 · ${props.value.length} 项`
  : props.value && typeof props.value === 'object' ? `配置对象 · ${Object.keys(props.value).length} 个字段`
    : `长文本 · ${text.value.length.toLocaleString('zh-CN')} 个字符`);
watch(() => [props.value, props.sensitive], () => { copyState.value = ''; });
async function copy() {
  if (props.sensitive || props.value == null) return;
  try { await navigator.clipboard.writeText(text.value); copyState.value = '已复制'; }
  catch { copyState.value = '复制失败，请展开后手动选择内容'; }
}
</script>
<template>
  <div class="managed-value" :class="{ secret: sensitive }">
    <details v-if="expandedValue" class="managed-value-detail">
      <summary><strong>{{ summary }}</strong><span>{{ $slots.editor ? '展开编辑' : '展开完整内容' }}</span></summary>
      <slot name="editor"><pre tabindex="0" :aria-label="`${label}完整内容`">{{ text }}</pre></slot>
    </details>
    <span v-else class="managed-readonly-value" :class="{ secret: sensitive }">{{ text }}</span>
    <button v-if="!sensitive && value != null" type="button" class="managed-value-copy" :aria-label="`复制${label}`" @click="copy"><Copy :size="14" /><span>{{ copyState === '已复制' ? '已复制' : '复制' }}</span></button>
    <span v-if="copyState" class="managed-value-feedback" role="status">{{ copyState }}</span>
  </div>
</template>
<style scoped>
.managed-value { position:relative; min-width:0; }
.managed-readonly-value { padding-right:76px; }
.managed-readonly-value.secret { padding-right:12px; }
.managed-value-detail { border:1px solid var(--oa-border-subtle); border-radius:8px; background:var(--oa-bg-subtle); }
.managed-value-detail > summary { padding:9px 78px 9px 12px; cursor:pointer; color:var(--oa-text-primary); line-height:22px; }
.managed-value-detail > summary strong { font-size:13px; font-weight:500; }
.managed-value-detail > summary span { margin-left:10px; color:var(--oa-text-secondary); font-size:12px; }
.managed-value-detail[open] > summary { border-bottom:1px solid var(--oa-border-subtle); }
.managed-value-detail pre { margin:0; padding:12px; max-height:280px; overflow:auto; white-space:pre-wrap; overflow-wrap:anywhere; font:12px/1.7 var(--oa-font-mono); color:var(--oa-text-secondary); }
.managed-value-copy { position:absolute; top:7px; right:8px; display:flex; align-items:center; gap:5px; padding:3px 5px; border:0; border-radius:4px; background:var(--oa-bg-subtle); color:var(--oa-text-secondary); font-size:11px; }
.managed-value-copy:hover { color:var(--oa-primary); background:var(--oa-primary-soft); }
.managed-value-feedback { display:block; padding-top:4px; color:var(--oa-text-secondary); font-size:11px; }
</style>
