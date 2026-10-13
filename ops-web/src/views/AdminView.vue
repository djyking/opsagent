<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ClipboardList, Cpu, Play, CheckCircle2, XCircle } from "@lucide/vue";
import { adminApi } from "@/api/modules";
import type { AiTask, OperationLog, PageResponse } from "@/types/api";
import StatusBadge from "@/components/StatusBadge.vue";
import PaginationBar from "@/components/PaginationBar.vue";
const tab = ref<"audit" | "tasks">("audit");
const audits = ref<PageResponse<OperationLog>>({
  records: [],
  total: 0,
  pageNum: 1,
  pageSize: 10,
});
const tasks = ref<PageResponse<AiTask>>({
  records: [],
  total: 0,
  pageNum: 1,
  pageSize: 10,
});
const auditPage = ref(1);
const taskPage = ref(1);
const error = ref("");
const busy = ref<number>();
async function loadAudits() {
  try {
    audits.value = await adminApi.audits({
      pageNum: auditPage.value,
      pageSize: 10,
    });
  } catch (e) {
    error.value = e instanceof Error ? e.message : "加载失败";
  }
}
async function loadTasks() {
  try {
    tasks.value = await adminApi.tasks({
      pageNum: taskPage.value,
      pageSize: 10,
    });
  } catch (e) {
    error.value = e instanceof Error ? e.message : "加载失败";
  }
}
async function taskStatus(item: AiTask, status: string) {
  busy.value = item.id;
  try {
    await adminApi.taskStatus(item.id, status);
    await loadTasks();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "更新失败";
  } finally {
    busy.value = undefined;
  }
}
onMounted(() => Promise.all([loadAudits(), loadTasks()]));
</script>
<template>
  <div class="stack-page">
    <section class="page-lead">
      <div>
        <span class="eyebrow">ADMINISTRATION</span>
        <h2>系统管理</h2>
        <p>审阅业务审计轨迹并管理状态事件产生的 AI 后续任务。</p>
      </div>
    </section>
    <div class="tabs">
      <button :class="{ active: tab === 'audit' }" @click="tab = 'audit'">
        <ClipboardList :size="18" />操作审计</button
      ><button :class="{ active: tab === 'tasks' }" @click="tab = 'tasks'">
        <Cpu :size="18" />AI 任务
      </button>
    </div>
    <section class="panel">
      <div v-if="error" class="inline-error">{{ error }}</div>
      <template v-if="tab === 'audit'"
        ><div v-if="!audits.records.length" class="empty-state">
          <ClipboardList :size="36" /><strong>暂无审计记录</strong>
        </div>
        <div v-else class="record-list">
          <article v-for="item in audits.records" :key="item.id">
            <div class="record-icon"><ClipboardList :size="20" /></div>
            <div class="record-body">
              <header>
                <strong>{{ item.operationType }}</strong
                ><span class="mono">{{ item.bizType }} #{{ item.bizId }}</span>
              </header>
              <p>{{ item.content }}</p>
              <span
                >操作人 #{{ item.operator }} ·
                {{ new Date(item.createTime).toLocaleString("zh-CN") }}</span
              >
            </div>
          </article>
        </div>
        <PaginationBar
          v-if="audits.total"
          :page="auditPage"
          :page-size="10"
          :total="audits.total"
          @change="
            (p) => {
              auditPage = p;
              loadAudits();
            }
          " /></template
      ><template v-else
        ><div v-if="!tasks.records.length" class="empty-state">
          <Cpu :size="36" /><strong>暂无 AI 任务</strong>
        </div>
        <div v-else class="record-list">
          <article v-for="item in tasks.records" :key="item.id">
            <div class="record-icon violet"><Cpu :size="20" /></div>
            <div class="record-body">
              <header>
                <strong>{{ item.taskType }}</strong
                ><StatusBadge :value="item.status" />
              </header>
              <p>{{ item.requestPayload || "无任务输入" }}</p>
              <span
                >{{ item.bizType }} #{{ item.bizId }} ·
                {{ new Date(item.createTime).toLocaleString("zh-CN") }}</span
              >
            </div>
            <div class="row-actions">
              <button
                v-if="item.status === 'PENDING'"
                class="icon-button"
                title="开始处理"
                :disabled="busy === item.id"
                @click="taskStatus(item, 'PROCESSING')"
              >
                <Play :size="17" /></button
              ><button
                v-if="item.status === 'PROCESSING'"
                class="icon-button success"
                title="标记成功"
                :disabled="busy === item.id"
                @click="taskStatus(item, 'SUCCESS')"
              >
                <CheckCircle2 :size="17" /></button
              ><button
                v-if="['PENDING', 'PROCESSING'].includes(item.status)"
                class="icon-button danger"
                title="标记失败"
                :disabled="busy === item.id"
                @click="taskStatus(item, 'FAILED')"
              >
                <XCircle :size="17" />
              </button>
            </div>
          </article>
        </div>
        <PaginationBar
          v-if="tasks.total"
          :page="taskPage"
          :page-size="10"
          :total="tasks.total"
          @change="
            (p) => {
              taskPage = p;
              loadTasks();
            }
          "
      /></template>
    </section>
  </div>
</template>
