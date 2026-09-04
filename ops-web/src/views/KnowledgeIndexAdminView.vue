<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { DatabaseZap, RefreshCw, RotateCw, Wrench } from "@lucide/vue";
import { adminApi } from "@/api/modules";
import StatusBadge from "@/components/StatusBadge.vue";

const consistency = ref<Record<string, unknown>>({});
const failedTasks = ref<Record<string, unknown>[]>([]);
const reindexTask = ref<Record<string, unknown>>();
const repairDocumentId = ref<number>();
const busy = ref("");
const error = ref("");
const success = ref("");

const counts = computed(() => [
  ["已发布文档", Number(consistency.value.publishedDocumentCount || 0)],
  ["已索引文档", Number(consistency.value.indexedDocumentCount || 0)],
  ["待处理", Number(consistency.value.pendingDocumentCount || 0)],
  ["失败", Number(consistency.value.failedDocumentCount || 0)],
  ["ES 孤儿文档", Number(consistency.value.orphanEsDocumentCount || 0)],
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
  if (!confirm("全量重建会创建新物理索引，校验成功后原子切换 Alias。确认继续吗？")) return;
  busy.value = "reindex";
  error.value = "";
  success.value = "";
  try {
    const taskId = await adminApi.requestKnowledgeReindex();
    success.value = `Reindex 任务 #${taskId} 已提交`;
    for (let attempt = 0; attempt < 120; attempt += 1) {
      reindexTask.value = await adminApi.knowledgeReindexTask(taskId);
      const status = String(reindexTask.value.status || "");
      if (["SUCCESS", "FAILED"].includes(status)) break;
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
    <section class="panel index-overview">
      <header class="panel-header">
        <div>
          <span class="eyebrow">RAG INDEX OPERATIONS</span>
          <h3>知识索引管理</h3>
          <p>核对 MySQL 发布状态与 Elasticsearch Alias 中的实际索引数据。</p>
        </div>
        <div class="row-actions">
          <button class="button secondary" :disabled="busy === 'load'" @click="load">
            <RefreshCw :size="16" />刷新
          </button>
          <button class="button primary" :disabled="Boolean(busy)" @click="startReindex">
            <RotateCw :size="16" />{{ busy === "reindex" ? "重建中…" : "全量 Reindex" }}
          </button>
        </div>
      </header>
      <p v-if="error" class="inline-error">{{ error }}</p>
      <p v-if="success" class="inline-success">{{ success }}</p>
      <div class="index-metric-grid">
        <article v-for="item in counts" :key="String(item[0])">
          <DatabaseZap :size="19" /><strong>{{ item[1] }}</strong><span>{{ item[0] }}</span>
        </article>
      </div>
      <dl class="index-metadata">
        <div><dt>读 Alias</dt><dd>{{ consistency.indexAlias || "-" }}</dd></div>
        <div><dt>写 Alias</dt><dd>{{ consistency.writeAlias || "-" }}</dd></div>
        <div><dt>物理索引</dt><dd>{{ consistency.physicalIndex || consistency.currentIndex || "-" }}</dd></div>
        <div><dt>Embedding 模型</dt><dd>{{ consistency.embeddingModel || "-" }}</dd></div>
      </dl>
    </section>

    <section v-if="reindexTask" class="panel reindex-progress">
      <header class="panel-header"><div><span class="eyebrow">LATEST TASK</span><h3>Reindex 任务 #{{ reindexTask.id }}</h3></div><StatusBadge :value="String(reindexTask.status)" /></header>
      <div class="index-metric-grid compact-grid">
        <article><strong>{{ reindexTask.document_success || 0 }}</strong><span>成功文档</span></article>
        <article><strong>{{ reindexTask.document_failure || 0 }}</strong><span>失败文档</span></article>
        <article><strong>{{ reindexTask.chunk_total || 0 }}</strong><span>索引切片</span></article>
      </div>
      <p v-if="reindexTask.error_message" class="inline-error">{{ reindexTask.error_message }}</p>
    </section>

    <section class="panel">
      <header class="panel-header">
        <div><span class="eyebrow">TARGETED REPAIR</span><h3>单文档修复</h3><p>重新投递指定文档的索引事件，适用于 FAILED 或人工核查后的补偿。</p></div>
      </header>
      <form class="index-repair-form" @submit.prevent="repair()">
        <input v-model.number="repairDocumentId" min="1" type="number" placeholder="文档 ID" />
        <button class="button primary" :disabled="Boolean(busy) || !repairDocumentId"><Wrench :size="16" />提交修复</button>
      </form>
    </section>

    <section class="panel">
      <header class="panel-header"><div><span class="eyebrow">FAILED TASKS</span><h3>失败索引任务</h3></div><span class="panel-count">{{ failedTasks.length }}</span></header>
      <div v-if="!failedTasks.length" class="empty-state">当前没有失败的索引任务</div>
      <div v-else class="index-task-table">
        <article v-for="task in failedTasks" :key="String(task.id)">
          <code>#{{ task.id }}</code>
          <div><strong>文档 #{{ task.documentId }}</strong><span>{{ task.operation }} · 重试 {{ task.retryCount || 0 }} 次</span><p>{{ task.lastError || "无错误详情" }}</p></div>
          <button class="button secondary small" :disabled="Boolean(busy)" @click="repair(Number(task.documentId))">重新投递</button>
        </article>
      </div>
    </section>
  </div>
</template>
