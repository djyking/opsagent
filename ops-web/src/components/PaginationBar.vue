<script setup lang="ts">
import { ChevronLeft, ChevronRight } from "@lucide/vue";
import { computed } from "vue";
const props = defineProps<{ page: number; pageSize: number; total: number }>();
const pages = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.pageSize))));
defineEmits<{ change: [page: number] }>();
</script>
<template>
  <div class="pagination">
    <span>共 {{ total }} 条</span>
    <div v-if="pages > 1" class="pagination-controls">
      <button
        class="icon-button"
        aria-label="上一页"
        :disabled="page <= 1"
        @click="$emit('change', page - 1)"
      >
        <ChevronLeft :size="18" /></button>
      <span aria-live="polite">第 {{ page }} / {{ pages }} 页</span>
      <button
        class="icon-button"
        aria-label="下一页"
        :disabled="page * pageSize >= total"
        @click="$emit('change', page + 1)"
      >
        <ChevronRight :size="18" />
      </button>
    </div>
  </div>
</template>
