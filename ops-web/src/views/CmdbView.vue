<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from "vue";
import { GraphChart } from "echarts/charts";
import { TooltipComponent } from "echarts/components";
import { getInstanceByDom, init, use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import { Link, Network, Pencil, Plus, Search, Server } from "@lucide/vue";
import { itsmApi } from "@/api/modules";
import BaseModal from "@/components/BaseModal.vue";
import DetailPanel from "@/components/DetailPanel.vue";
import PageHeader from "@/components/PageHeader.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import StatusBadge from "@/components/StatusBadge.vue";
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
const inspectorOpen = ref(false);
const ciForm = ref({ ciCode: "", ciName: "", ciType: "SERVICE", environment: "PROD", ownerName: "", endpoint: "", status: "ACTIVE", description: "" });
const relationForm = ref({ sourceCiCode: "", targetCiCode: "", relationType: "DEPENDS_ON", description: "" });
const selectedCi = computed(() => cis.value.find((row) => String(row.ciCode) === selected.value));
const typeLabels: Record<string, string> = { SERVICE: "服务", DATABASE: "数据库", CACHE: "缓存", QUEUE: "消息队列", MESSAGE_QUEUE: "消息队列", GATEWAY: "网关", ALERT: "告警", MONITOR: "监控", REGISTRY: "注册中心", SEARCH: "搜索", VECTOR_DB: "向量库" };
const environmentLabels: Record<string, string> = { PROD: "生产", STAGING: "预发布", TEST: "测试", DEV: "开发" };
const relationLabels: Record<string, string> = { DEPENDS_ON: "依赖", CALLS: "调用", READS: "读取", WRITES: "写入", PUBLISHES_TO: "发布到", CONSUMES_FROM: "消费自", ROUTES_TO: "路由到", SENDS_TO: "发送至" };
function displayLabel(map: Record<string, string>, value: unknown) { const raw = String(value || ""); return map[raw] || raw || "—"; }
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
        edgeSymbol: ["none", "arrow"],
        edgeSymbolSize: 7,
        force: { repulsion: 260, edgeLength: 130 },
        data: nodes.map((node) => ({
          id: String(node.ciCode),
          name: String(node.ciName),
          symbolSize: node.ciCode === selected.value ? 58 : 42,
          itemStyle: { color: node.ciCode === selected.value ? "#416fe5" : ({ DATABASE: "#64748b", CACHE: "#2f8068", QUEUE: "#7656c5" } as Record<string, string>)[String(node.ciType)] || "#7b8da1" },
        })),
        links: edges.map((edge) => ({
          source: String(edge.sourceCiCode),
          target: String(edge.targetCiCode),
          label: { show: true, formatter: relationLabels[String(edge.relationType)] || String(edge.relationType), fontSize: 10 },
          lineStyle: { color: "#b5bdc9", width: 1.2 },
        })),
      },
    ],
  });
}

async function selectCi(code: string) {
  selected.value = code;
  await draw();
  if (window.innerWidth < 1600) inspectorOpen.value = true;
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
    <PageHeader title="服务目录与依赖拓扑" description="从配置项查看服务、中间件与真实调用依赖" />
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
    <section class="itsm-split">
      <aside class="panel catalog-panel">
        <header class="panel-header"><div><h3>配置项</h3></div><div class="row-actions"><span class="panel-count">{{ filtered.length }}</span><button v-if="auth.isAdmin" class="icon-button" title="新增配置项" @click="openCi()"><Plus :size="16" /></button></div></header>
        <div class="search-box compact"><Search :size="16" /><input v-model="keyword" placeholder="搜索名称、编码或类型" /></div>
        <button v-for="ci in filtered" :key="String(ci.ciCode)" class="catalog-item" :class="{ active: selected === ci.ciCode }" @click="selectCi(String(ci.ciCode))">
          <Server :size="18" /><span><strong>{{ ci.ciName }}</strong><small>{{ ci.ciCode }} · <span :title="String(ci.ciType)">{{ displayLabel(typeLabels, ci.ciType) }}</span></small></span><StatusBadge :value="String(ci.status)" />
        </button>
        <LoadingState v-if="loading" text="正在读取服务目录…" compact />
      </aside>
      <main class="panel topology-panel">
        <header class="panel-header"><div><h3><Network :size="18" />{{ selectedCi?.ciName || "依赖拓扑" }}</h3><p><code>{{ selected || "未选择 CI" }}</code></p></div><div class="row-actions"><button class="button secondary" @click="inspectorOpen = true">查看详情</button><button v-if="auth.isAdmin" class="button secondary" @click="openCi(selectedCi)"><Pencil :size="15" />编辑</button><button v-if="auth.isAdmin" class="button secondary" @click="openRelation"><Link :size="15" />新增依赖</button></div></header>
        <div ref="chartElement" class="topology-chart" />
      </main>
      <aside v-if="selectedCi" class="panel cmdb-inspector"><header class="panel-header"><div><h3>配置项详情</h3><p>当前拓扑上下文</p></div></header><dl class="oa-definition-list"><div><dt>名称</dt><dd>{{ selectedCi.ciName }}</dd></div><div><dt>CI 编码</dt><dd><code>{{ selectedCi.ciCode }}</code></dd></div><div><dt>类型</dt><dd :title="String(selectedCi.ciType)">{{ displayLabel(typeLabels, selectedCi.ciType) }}</dd></div><div><dt>环境</dt><dd :title="String(selectedCi.environment)">{{ displayLabel(environmentLabels, selectedCi.environment) }}</dd></div><div><dt>状态</dt><dd><StatusBadge :value="String(selectedCi.status)" /></dd></div><div><dt>负责人</dt><dd>{{ selectedCi.ownerName || '未设置' }}</dd></div><div><dt>Endpoint</dt><dd><code>{{ selectedCi.endpoint || '未设置' }}</code></dd></div><div><dt>说明</dt><dd>{{ selectedCi.description || '暂无说明' }}</dd></div></dl></aside>
    </section>
    <DetailPanel v-if="inspectorOpen && selectedCi" title="配置项详情" :subtitle="String(selectedCi.ciCode)" @close="inspectorOpen = false"><dl class="oa-definition-list"><div><dt>名称</dt><dd>{{ selectedCi.ciName }}</dd></div><div><dt>类型</dt><dd :title="String(selectedCi.ciType)">{{ displayLabel(typeLabels, selectedCi.ciType) }}</dd></div><div><dt>环境</dt><dd :title="String(selectedCi.environment)">{{ displayLabel(environmentLabels, selectedCi.environment) }}</dd></div><div><dt>状态</dt><dd><StatusBadge :value="String(selectedCi.status)" /></dd></div><div><dt>负责人</dt><dd>{{ selectedCi.ownerName || '未设置' }}</dd></div><div><dt>Endpoint</dt><dd><code>{{ selectedCi.endpoint || '未设置' }}</code></dd></div><div><dt>说明</dt><dd>{{ selectedCi.description || '暂无说明' }}</dd></div></dl></DetailPanel>
    <BaseModal v-if="showCiForm" :title="editingId ? '编辑配置项' : '新增配置项'" @close="showCiForm = false"><form class="form-grid" @submit.prevent="saveCi"><label>CI 编码<input v-model.trim="ciForm.ciCode" required maxlength="64" /></label><label>名称<input v-model.trim="ciForm.ciName" required maxlength="128" /></label><label>类型<select v-model="ciForm.ciType"><option value="SERVICE">服务</option><option value="DATABASE">数据库</option><option value="CACHE">缓存</option><option value="QUEUE">消息队列</option><option value="GATEWAY">网关</option></select></label><label>环境<select v-model="ciForm.environment"><option value="PROD">生产</option><option value="STAGING">预发布</option><option value="TEST">测试</option><option value="DEV">开发</option></select></label><label>负责人<input v-model.trim="ciForm.ownerName" maxlength="64" /></label><label>状态<select v-model="ciForm.status"><option value="ACTIVE">正常</option><option value="INACTIVE">停用</option></select></label><label class="full">访问地址<input v-model.trim="ciForm.endpoint" maxlength="255" /></label><label class="full">说明<textarea v-model.trim="ciForm.description" rows="3" maxlength="500" /></label><p v-if="error" class="form-error full">{{ error }}</p><div class="form-actions full"><button type="button" class="button secondary" @click="showCiForm = false">取消</button><button class="button primary" :disabled="saving">{{ saving ? "保存中…" : "保存" }}</button></div></form></BaseModal>
    <BaseModal v-if="showRelationForm" title="新增服务依赖" @close="showRelationForm = false"><form class="form-grid" @submit.prevent="saveRelation"><label>源 CI<select v-model="relationForm.sourceCiCode"><option v-for="ci in cis" :key="String(ci.ciCode)" :value="ci.ciCode">{{ ci.ciName }}</option></select></label><label>目标 CI<select v-model="relationForm.targetCiCode" required><option value="" disabled>请选择</option><option v-for="ci in cis.filter((row) => row.ciCode !== relationForm.sourceCiCode)" :key="String(ci.ciCode)" :value="ci.ciCode">{{ ci.ciName }}</option></select></label><label>关系类型<select v-model="relationForm.relationType"><option v-for="(label, value) in relationLabels" :key="value" :value="value">{{ label }}</option></select></label><label>说明<input v-model.trim="relationForm.description" maxlength="500" /></label><p v-if="error" class="form-error full">{{ error }}</p><div class="form-actions full"><button type="button" class="button secondary" @click="showRelationForm = false">取消</button><button class="button primary" :disabled="saving">{{ saving ? "保存中…" : "保存依赖" }}</button></div></form></BaseModal>
  </div>
</template>
