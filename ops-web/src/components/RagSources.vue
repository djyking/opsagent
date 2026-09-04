<script setup lang="ts">
import { computed } from "vue";
import { Database } from "@lucide/vue";
import type { AiReference } from "@/types/api";
import { useAuthStore } from "@/stores/auth";

const props = withDefaults(
  defineProps<{
    references: AiReference[];
    compact?: boolean;
  }>(),
  { compact: false },
);

const auth = useAuthStore();
const rows = computed(() => props.references || []);

function pages(item: AiReference) {
  const start = item.pageStart ?? item.pageNumber;
  if (start == null) return "";
  const end = item.pageEnd;
  return end != null && end !== start ? `第 ${start}-${end} 页` : `第 ${start} 页`;
}

function channels(item: AiReference) {
  return (item.retrievalChannels || []).join(" + ");
}
</script>

<template>
  <section v-if="rows.length" class="source-section" :class="{ compact }">
    <strong><Database :size="16" />参考来源</strong>
    <div class="rag-source-list">
      <article v-for="item in rows" :key="item.chunkId">
        <span class="source-id">[{{ item.sourceId || `C${item.chunkIndex}` }}]</span>
        <div>
          <strong>{{ item.documentName || `文档 #${item.documentId}` }}</strong>
          <p v-if="item.headingPath">{{ item.headingPath }}</p>
          <small>
            <span v-if="pages(item)">{{ pages(item) }}</span>
            <span v-if="channels(item)">{{ channels(item) }}</span>
            <span v-if="item.neighbor">邻近上下文</span>
            <span v-if="item.relevanceScore">相关度 {{ (item.relevanceScore * 100).toFixed(0) }}%</span>
          </small>
          <small v-if="auth.isAdmin && (item.rrfScore != null || item.rerankScore != null)" class="source-debug">
            RRF {{ item.rrfScore?.toFixed(5) || "-" }} · Rerank {{ item.rerankScore?.toFixed(5) || "未启用" }}
          </small>
        </div>
      </article>
    </div>
  </section>
</template>
