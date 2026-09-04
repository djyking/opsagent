<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from "vue";
import { GraphChart } from "echarts/charts";
import { TooltipComponent } from "echarts/components";
import { getInstanceByDom, init, use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import { Link, Network, Pencil, Plus, Search, Server } from "@lucide/vue";
import { itsmApi } from "@/api/modules";
import BaseModal from "@/components/BaseModal.vue";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
use([GraphChart, TooltipComponent, CanvasRenderer]);
const cis = ref<Record<string, unknown>[]>([]);
const selected = ref("");
const keyword = ref("");
const loading = ref(false);
const error = ref("");
const chartElement = ref<HTMLElement>();
const showCiForm = ref(false);
const showRelationForm = ref(false);
const saving = ref(false);
const editingId = ref(0);
const ciForm = ref({ ciCode: "", ciName: "", ciType: "SERVICE", environment: "PROD", ownerName: "", endpoint: "", status: "ACTIVE", description: "" });
const relationForm = ref({ sourceCiCode: "", targetCiCode: "", relationType: "DEPENDS_ON", description: "" });
const filtered = computed(() => {
  const term = keyword.value.trim().toLowerCase();
  if (!term) return cis.value;
  return cis.value.filter((row) =>
    `${row.ciCode} ${row.ciName} ${row.ciType}`.toLowerCase().includes(term),
  );
});

async function load() {
  loading.value = true;
  try {
    cis.value = await itsmApi.cis();
    if (!selected.value && cis.value.length) selected.value = String(cis.value[0].ciCode);
    await draw();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "CMDB 加载失败";
  } finally {
    loading.value = false;
  }
}

async function draw() {
  if (!selected.value) return;
  const topology = await itsmApi.topology(selected.value);
  await nextTick();
  if (!chartElement.value) return;
  const nodes = (topology.nodes as Record<string, unknown>[]) || [];
  const edges = (topology.edges as Record<string, unknown>[]) || [];
  const chart = getInstanceByDom(chartElement.value) || init(chartElement.value);
  chart.setOption({
    tooltip: { formatter: "{b}" },
    series: [
      {
        type: "graph",
        layout: "force",
        roam: true,
        label: { show: true, position: "bottom" },
        force: { repulsion: 260, edgeLength: 130 },
        data: nodes.map((node) => ({
          id: String(node.ciCode),
          name: String(node.ciName),
          symbolSize: node.ciCode === selected.value ? 58 : 42,
          itemStyle: { color: node.ciCode === selected.value ? "#195dcc" : "#7b8da1" },
        })),
        links: edges.map((edge) => ({
          source: String(edge.sourceCiCode),
          target: String(edge.targetCiCode),
          label: { show: true, formatter: String(edge.relationType), fontSize: 9 },
        })),
      },
    ],
  });
}

async function selectCi(code: string) {
  selected.value = code;
  await draw();
}

function openCi(row?: Record<string, unknown>) {
  editingId.value = Number(row?.id || 0);
  ciForm.value = row
    ? {
        ciCode: String(row.ciCode || ""), ciName: String(row.ciName || ""),
        ciType: String(row.ciType || "SERVICE"), environment: String(row.environment || "PROD"),
        ownerName: String(row.ownerName || ""), endpoint: String(row.endpoint || ""),
        status: String(row.status || "ACTIVE"), description: String(row.description || ""),
      }
    : { ciCode: "", ciName: "", ciType: "SERVICE", environment: "PROD", ownerName: "", endpoint: "", status: "ACTIVE", description: "" };
  showCiForm.value = true;
}

async function saveCi() {
  saving.value = true;
  error.value = "";
  try {
    if (editingId.value) await itsmApi.updateCi(editingId.value, ciForm.value);
    else await itsmApi.createCi(ciForm.value);
    showCiForm.value = false;
    await load();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "配置项保存失败";
  } finally { saving.value = false; }
}

function openRelation() {
  relationForm.value = { sourceCiCode: selected.value, targetCiCode: "", relationType: "DEPENDS_ON", description: "" };
  showRelationForm.value = true;
}

async function saveRelation() {
  saving.value = true;
  error.value = "";
  try {
    await itsmApi.createRelation(relationForm.value);
    showRelationForm.value = false;
    await draw();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "依赖关系保存失败";
  } finally { saving.value = false; }
}

onMounted(load);
</script>

<template>
  <div class="stack-page">
    <section class="page-lead">
      <div><span class="eyebrow">CMDB LITE</span><h2>服务目录与依赖拓扑</h2><p>从配置项出发查看服务、中间件和调用依赖，并可关联到工单。</p></div>
    </section>
    <p v-if="error" class="inline-error">{{ error }}</p>
    <section class="itsm-split">
      <aside class="panel catalog-panel">
        <header class="panel-header"><div><span class="eyebrow">CONFIGURATION ITEMS</span><h3>配置项</h3></div><div class="row-actions"><span class="panel-count">{{ filtered.length }}</span><button v-if="auth.isAdmin" class="icon-button" title="新增配置项" @click="openCi()"><Plus :size="16" /></button></div></header>
        <div class="search-box compact"><Search :size="16" /><input v-model="keyword" placeholder="搜索名称、编码或类型" /></div>
        <button v-for="ci in filtered" :key="String(ci.ciCode)" class="catalog-item" :class="{ active: selected === ci.ciCode }" @click="selectCi(String(ci.ciCode))">
          <Server :size="18" /><span><strong>{{ ci.ciName }}</strong><small>{{ ci.ciCode }} · {{ ci.ciType }}</small></span><i :class="String(ci.status).toLowerCase()">{{ ci.status }}</i>
        </button>
        <div v-if="loading" class="loading-state">正在读取服务目录…</div>
      </aside>
      <main class="panel topology-panel">
        <header class="panel-header"><div><span class="eyebrow">DEPENDENCY GRAPH</span><h3><Network :size="18" />{{ selected || "服务拓扑" }}</h3></div><div v-if="auth.isAdmin" class="row-actions"><button class="button secondary" @click="openCi(cis.find((row) => row.ciCode === selected))"><Pencil :size="15" />编辑 CI</button><button class="button secondary" @click="openRelation"><Link :size="15" />新增依赖</button></div><small v-else>可缩放、拖动节点</small></header>
        <div ref="chartElement" class="topology-chart" />
      </main>
    </section>
    <BaseModal v-if="showCiForm" :title="editingId ? '编辑配置项' : '新增配置项'" @close="showCiForm = false"><form class="form-grid" @submit.prevent="saveCi"><label>CI 编码<input v-model.trim="ciForm.ciCode" required maxlength="64" /></label><label>名称<input v-model.trim="ciForm.ciName" required maxlength="128" /></label><label>类型<input v-model.trim="ciForm.ciType" required maxlength="32" /></label><label>环境<select v-model="ciForm.environment"><option>PROD</option><option>STAGING</option><option>TEST</option></select></label><label>负责人<input v-model.trim="ciForm.ownerName" maxlength="64" /></label><label>状态<select v-model="ciForm.status"><option>ACTIVE</option><option>INACTIVE</option></select></label><label class="full">访问地址<input v-model.trim="ciForm.endpoint" maxlength="255" /></label><label class="full">说明<textarea v-model.trim="ciForm.description" rows="3" maxlength="500" /></label><p v-if="error" class="form-error full">{{ error }}</p><div class="form-actions full"><button type="button" class="button secondary" @click="showCiForm = false">取消</button><button class="button primary" :disabled="saving">{{ saving ? "保存中…" : "保存" }}</button></div></form></BaseModal>
    <BaseModal v-if="showRelationForm" title="新增服务依赖" @close="showRelationForm = false"><form class="form-grid" @submit.prevent="saveRelation"><label>源 CI<select v-model="relationForm.sourceCiCode"><option v-for="ci in cis" :key="String(ci.ciCode)" :value="ci.ciCode">{{ ci.ciName }}</option></select></label><label>目标 CI<select v-model="relationForm.targetCiCode" required><option value="" disabled>请选择</option><option v-for="ci in cis.filter((row) => row.ciCode !== relationForm.sourceCiCode)" :key="String(ci.ciCode)" :value="ci.ciCode">{{ ci.ciName }}</option></select></label><label>关系类型<select v-model="relationForm.relationType"><option>DEPENDS_ON</option><option>CALLS</option><option>READS</option><option>WRITES</option><option>PUBLISHES_TO</option><option>CONSUMES_FROM</option><option>ROUTES_TO</option></select></label><label>说明<input v-model.trim="relationForm.description" maxlength="500" /></label><p v-if="error" class="form-error full">{{ error }}</p><div class="form-actions full"><button type="button" class="button secondary" @click="showRelationForm = false">取消</button><button class="button primary" :disabled="saving">{{ saving ? "保存中…" : "保存依赖" }}</button></div></form></BaseModal>
  </div>
</template>
