<script setup lang="ts">
import { ChevronLeft, ChevronRight } from "@lucide/vue";
const props = defineProps<{ page: number; pageSize: number; total: number }>();
defineEmits<{ change: [page: number] }>();
</script>
<template>
  <div class="pagination">
    <span
      >共 {{ total }} 条 · 第 {{ page }} /
      {{ Math.max(1, Math.ceil(total / pageSize)) }} 页</span
    >
    <div>
      <button
        class="icon-button"
        :disabled="page <= 1"
        @click="$emit('change', page - 1)"
      >
        <ChevronLeft :size="18" /></button
      ><button
        class="icon-button"
        :disabled="page * pageSize >= total"
        @click="$emit('change', page + 1)"
      >
        <ChevronRight :size="18" />
      </button>
    </div>
  </div>
</template>
