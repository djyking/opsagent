<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { AlertTriangle, Check, ChevronDown, DatabaseZap, RefreshCw, RotateCw, Wrench } from "@lucide/vue";
import { adminApi } from "@/api/modules";
import DetailPanel from "@/components/DetailPanel.vue";
import MetricStrip from "@/components/MetricStrip.vue";
import type { MetricStripItem } from "@/components/MetricStrip.vue";
import PageHeader from "@/components/PageHeader.vue";
import StatusBadge from "@/components/StatusBadge.vue";

const consistency = ref<Record<string, unknown>>({});
const failedTasks = ref<Record<string, unknown>[]>([]);
const reindexTask = ref<Record<string, unknown>>();
const repairDocumentId = ref<number>();
const repairOpen = ref(false);
const busy = ref("");
const error = ref("");
const success = ref("");

const healthMetrics = computed<MetricStripItem[]>(() => [
  { key: "published", label: "已发布", value: Number(consistency.value.publishedDocumentCount || 0), meta: "MySQL 发布文档", icon: DatabaseZap },
  { key: "indexed", label: "ES 文档", value: Number(consistency.value.indexedDocumentCount || 0), meta: "Elasticsearch 已索引", icon: DatabaseZap },
  { key: "vectors", label: "Qdrant Points", value: Number(consistency.value.vectorPointCount || 0), meta: "当前 Alias 向量点", icon: DatabaseZap },
  { key: "pending", label: "待处理", value: Number(consistency.value.pendingDocumentCount || 0), meta: "等待索引管线处理", tone: Number(consistency.value.pendingDocumentCount || 0) ? "warning" : "default", icon: DatabaseZap },
  { key: "failed", label: "失败", value: Number(consistency.value.failedDocumentCount || 0), meta: "需要人工补偿", tone: Number(consistency.value.failedDocumentCount || 0) ? "danger" : "default", icon: AlertTriangle },
]);

const checks = computed(() => [
  { label: "Elasticsearch 无孤儿文档", count: Number(consistency.value.orphanEsDocumentCount || 0), unit: "篇" },
  { label: "Qdrant 无缺失向量", count: Number(consistency.value.missingQdrantPointCount || 0), unit: "points" },
  { label: "Qdrant 无孤儿向量", count: Number(consistency.value.orphanQdrantPointCount || 0), unit: "points" },
]);

async function load() {
  busy.value = "load";
  error.value = "";
  try {
    [consistency.value, failedTasks.value] = await Promise.all([
      adminApi.knowledgeIndexConsistency(),
      adminApi.failedKnowledgeIndexTasks(),
    ]);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "索引状态读取失败";
  } finally {
    busy.value = "";
  }
}

async function startReindex() {
  if (busy.value) return;
  if (!confirm("全量重建会创建新的 ES 索引和 Qdrant Collection，校验成功后协调切换两侧 Alias。确认继续吗？")) return;
  busy.value = "reindex";
  error.value = "";
  success.value = "";
  try {
    const taskId = await adminApi.requestKnowledgeReindex();
    success.value = `Reindex 任务 #${taskId} 已提交`;
    for (let attempt = 0; attempt < 120; attempt += 1) {
      reindexTask.value = await adminApi.knowledgeReindexTask(taskId);
      if (["SUCCESS", "FAILED"].includes(String(reindexTask.value.status || ""))) break;
      await new Promise((resolve) => window.setTimeout(resolve, 2_000));
    }
    await load();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "Reindex 提交失败";
  } finally {
    busy.value = "";
  }
}

async function repair(documentId?: number) {
  const id = Number(documentId || repairDocumentId.value);
  if (!Number.isInteger(id) || id <= 0 || busy.value) {
    error.value = "请输入有效的文档 ID";
    return;
  }
  busy.value = `repair-${id}`;
  error.value = "";
  try {
    const taskId = await adminApi.repairKnowledgeIndex(id);
    success.value = `文档 #${id} 的索引修复任务 #${taskId} 已进入 Outbox 队列`;
    repairDocumentId.value = undefined;
    repairOpen.value = false;
    await load();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "索引修复失败";
  } finally {
    busy.value = "";
  }
}

onMounted(load);
</script>

<template>
  <div class="index-admin-page">
    <PageHeader title="知识索引" description="检查 MySQL、Elasticsearch 与 Qdrant 的发布一致性">
      <template #actions>
        <button class="button secondary" :disabled="busy === 'load'" @click="load"><RefreshCw :size="15" />{{ busy === "load" ? "检查中…" : "刷新" }}</button>
        <details class="maintenance-menu">
          <summary class="button secondary">维护操作<ChevronDown :size="14" /></summary>
          <div>
            <button @click="load"><RefreshCw :size="15" /><span><strong>重新检查一致性</strong><small>刷新当前索引健康状态</small></span></button>
            <button @click="repairOpen = true"><Wrench :size="15" /><span><strong>单文档修复</strong><small>重新投递指定文档</small></span></button>
            <button class="danger" :disabled="Boolean(busy)" @click="startReindex"><RotateCw :size="15" /><span><strong>全量 Reindex</strong><small>重建索引并切换 Alias</small></span></button>
          </div>
        </details>
      </template>
    </PageHeader>

    <p v-if="error" class="inline-error">{{ error }}</p>
    <p v-if="success" class="inline-success">{{ success }}</p>

    <section class="panel index-health-surface">
      <header class="section-header"><div><h3>索引健康</h3><p>来自后端一致性检查的实时结果</p></div><span class="health-indicator" :class="{ issue: checks.some((item) => item.count) }"><i />{{ checks.some((item) => item.count) ? "需要处理" : "一致" }}</span></header>
      <MetricStrip :items="healthMetrics" label="索引健康指标" />
      <div class="index-details-grid">
        <section>
          <header class="section-header compact"><div><h3>索引配置</h3><p>当前读写 Alias 与物理资源</p></div></header>
          <dl class="index-definition-list">
            <div><dt>读取 Alias</dt><dd>{{ consistency.indexAlias || "-" }}</dd></div>
            <div><dt>写入 Alias</dt><dd>{{ consistency.writeAlias || "-" }}</dd></div>
            <div><dt>ES Index</dt><dd>{{ consistency.physicalIndex || consistency.currentIndex || "-" }}</dd></div>
            <div><dt>Qdrant Alias</dt><dd>{{ consistency.vectorAlias || "-" }}</dd></div>
            <div><dt>Qdrant Collection</dt><dd>{{ consistency.physicalCollection || "-" }}</dd></div>
            <div><dt>Embedding</dt><dd>{{ consistency.embeddingModel || "-" }}</dd></div>
          </dl>
        </section>
        <section>
          <header class="section-header compact"><div><h3>一致性检查</h3><p>孤儿数据与缺失向量</p></div></header>
          <div class="consistency-checklist">
            <div v-for="item in checks" :key="item.label" :class="{ issue: item.count }"><span><Check v-if="!item.count" :size="15" /><AlertTriangle v-else :size="15" /></span><strong>{{ item.count ? `${item.label.replace('无', '')} ${item.count} ${item.unit}` : item.label }}</strong><button v-if="item.count" class="text-button" @click="repairOpen = true">处理</button></div>
          </div>
        </section>
      </div>
    </section>

    <section v-if="reindexTask" class="panel reindex-progress compact-surface">
      <header class="section-header"><div><h3>最近 Reindex 任务 #{{ reindexTask.id }}</h3><p>成功 {{ reindexTask.document_success || 0 }} 篇 · 失败 {{ reindexTask.document_failure || 0 }} 篇 · 切片 {{ reindexTask.chunk_total || 0 }}</p></div><StatusBadge :value="String(reindexTask.status)" /></header>
      <p v-if="reindexTask.error_message" class="inline-error">{{ reindexTask.error_message }}</p>
    </section>

    <section class="panel failed-task-surface">
      <header class="section-header"><div><h3>失败任务</h3><p>索引管线中等待人工处理的任务</p></div><span class="panel-count">{{ failedTasks.length }}</span></header>
      <div v-if="!failedTasks.length" class="inline-empty"><Check :size="17" />当前没有失败的索引任务</div>
      <div v-else class="index-task-table">
        <article v-for="task in failedTasks" :key="String(task.id)">
          <code>#{{ task.id }}</code>
          <div><strong>文档 #{{ task.documentId }}</strong><span>{{ task.operation }} · 重试 {{ task.retryCount || 0 }} 次</span><p>{{ task.lastError || "无错误详情" }}</p></div>
          <button class="button secondary small" :disabled="Boolean(busy)" @click="repair(Number(task.documentId))">重新投递</button>
        </article>
      </div>
    </section>

    <DetailPanel v-if="repairOpen" title="维护工具" subtitle="KNOWLEDGE INDEX" @close="repairOpen = false">
      <section class="maintenance-drawer-copy"><Wrench :size="20" /><div><h3>单文档修复</h3><p>重新投递指定文档的索引事件，适用于失败任务或人工核查后的补偿。</p></div></section>
      <form class="index-repair-form drawer-form" @submit.prevent="repair()">
        <label>文档 ID<input v-model.number="repairDocumentId" min="1" type="number" placeholder="输入正整数文档 ID" /></label>
        <div><button type="button" class="button secondary" @click="repairOpen = false">取消</button><button class="button primary" :disabled="Boolean(busy) || !repairDocumentId"><RotateCw :size="15" />重新索引</button></div>
      </form>
    </DetailPanel>
  </div>
</template>
