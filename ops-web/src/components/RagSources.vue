<script setup lang="ts">
import { computed } from "vue";
import { ArrowUpRight, Database } from "@lucide/vue";
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
function sourceTime(value?: string) {
  if (!value) return "";
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? date.toLocaleString("zh-CN", { hour12: false }) : "";
}
function officialUrl(item: AiReference) {
  if (item.sourceType !== 'OFFICIAL_WEB' || !item.sourceUrl) return undefined;
  try {
    const url = new URL(item.sourceUrl);
    return url.protocol === 'https:' && !url.username && !url.password
      && ['redis.io', 'nacos.io', 'sentinelguard.io', 'www.rabbitmq.com', 'dev.mysql.com', 'prometheus.io', 'docs.docker.com', 'kubernetes.io', 'www.elastic.co'].includes(url.hostname)
      ? url.href : undefined;
  } catch { return undefined; }
}
</script>

<template>
  <section v-if="rows.length" class="source-section" :class="{ compact }">
    <strong><Database :size="16" />参考来源</strong>
    <div class="rag-source-list">
      <article v-for="item in rows" :key="`${item.sourceType || 'DOCUMENT'}-${item.sourceId || item.chunkId}`" :class="{ 'cmdb-source': item.sourceType === 'CMDB' || item.sourceType === 'OPERATIONS' }">
        <span class="source-id">[{{ item.sourceId || `C${item.chunkIndex}` }}]</span>
        <div>
          <strong>{{ item.documentName || `文档 #${item.documentId}` }}</strong>
          <p v-if="item.headingPath">{{ item.headingPath }}</p>
          <template v-if="item.sourceType === 'CMDB' || item.sourceType === 'OPERATIONS'">
            <small>{{ item.sourceType === 'OPERATIONS' ? '运行数据 · 本次安全快照' : '服务目录 · 实时读取' }}</small>
            <small v-if="sourceTime(item.sourceRetrievedAt)">读取于 {{ sourceTime(item.sourceRetrievedAt) }}</small>
            <small v-if="sourceTime(item.sourceUpdatedAt)">{{ item.sourceType === 'OPERATIONS' ? '快照采集' : '记录更新' }} {{ sourceTime(item.sourceUpdatedAt) }}</small>
            <RouterLink v-if="item.sourceUrl === '/operations'" class="source-catalog-link" to="/operations">查看运维中心 <ArrowUpRight :size="13" /></RouterLink><RouterLink v-else-if="item.sourceUrl === '/tickets?view=alerts'" class="source-catalog-link" to="/tickets?view=alerts">查看原始告警 <ArrowUpRight :size="13" /></RouterLink><RouterLink v-else-if="item.sourceUrl === '/itsm/cmdb'" class="source-catalog-link" to="/itsm/cmdb">查看服务目录与关系 <ArrowUpRight :size="13" /></RouterLink>
          </template>
          <template v-else-if="item.sourceType === 'OFFICIAL_WEB'">
            <small>官方公开文档 · 通用技术参考</small>
            <small v-if="sourceTime(item.sourceRetrievedAt)">读取于 {{ sourceTime(item.sourceRetrievedAt) }}</small>
            <a v-if="officialUrl(item)" :href="officialUrl(item)" target="_blank" rel="noopener noreferrer" class="source-catalog-link">查看官方原文 <ArrowUpRight :size="13" /></a>
          </template>
          <template v-else-if="item.sourceType === 'OBSERVABILITY_EVIDENCE'">
            <small>本轮服务端观测证据</small>
            <small v-if="sourceTime(item.sourceUpdatedAt)">采样于 {{ sourceTime(item.sourceUpdatedAt) }}</small>
            <details v-if="item.evidenceId || item.evidenceBundleId"><summary>核对证据标识</summary><small>证据 {{ item.evidenceId || '未提供' }}</small><small>证据包 {{ item.evidenceBundleId || '未提供' }}</small></details>
          </template>
          <small v-else>
            <span v-if="pages(item)">{{ pages(item) }}</span>
            <span v-if="channels(item)">{{ channels(item) }}</span>
            <span v-if="item.neighbor">邻近上下文</span>
            <span v-if="item.relevanceScore">相关度 {{ (item.relevanceScore * 100).toFixed(0) }}%</span>
          </small>
          <small v-if="item.sourceType !== 'CMDB' && item.sourceType !== 'OPERATIONS' && auth.isAdmin && (item.rrfScore != null || item.rerankScore != null)" class="source-debug">
            RRF {{ item.rrfScore?.toFixed(5) || "-" }} · Rerank {{ item.rerankScore?.toFixed(5) || "未启用" }}
          </small>
        </div>
      </article>
    </div>
  </section>
</template>
