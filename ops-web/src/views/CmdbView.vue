<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from "vue";
import { Link, Network, Pencil, Plus, RefreshCw, Search } from "@lucide/vue";
import { itsmApi } from "@/api/modules";
import BaseModal from "@/components/BaseModal.vue";
import DetailPanel from "@/components/DetailPanel.vue";
import PageHeader from "@/components/PageHeader.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import EmptyState from "@/components/EmptyState.vue";
import CmdbTopologyGraph from "@/components/cmdb/CmdbTopologyGraph.vue";
import { ciType, relationNames } from "@/components/cmdb/topology";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const cis = ref<Record<string, unknown>[]>([]);
const selected = ref("");
const keyword = ref("");
const loading = ref(false);
const topologyLoading = ref(false);
const topologyNodes = ref<Record<string, unknown>[]>([]);
const topologyEdges = ref<Record<string, unknown>[]>([]);
const topologyError = ref("");
let topologyRequest = 0;
const error = ref("");
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
const relationLabels = relationNames;
function displayLabel(map: Record<string, string>, value: unknown) { const raw = String(value || ""); return map[raw] || raw || "—"; }
const filtered = computed(() => {
  const term = keyword.value.trim().toLowerCase();
  if (!term) return cis.value;
  return cis.value.filter((row) =>
    `${row.ciCode} ${row.ciName} ${row.ciType} ${displayLabel(typeLabels, row.ciType)}`.toLowerCase().includes(term),
  );
});

async function load() {
  loading.value = true;
  error.value = "";
  try {
    cis.value = await itsmApi.cis();
    if (!cis.value.some((ci) => String(ci.ciCode) === selected.value)) selected.value = String(cis.value[0]?.ciCode || "");
    await loadTopology();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "CMDB 加载失败";
  } finally {
    loading.value = false;
  }
}

async function loadTopology() {
  const requestId = ++topologyRequest;
  topologyError.value = "";
  if (!selected.value) { topologyNodes.value = []; topologyEdges.value = []; topologyLoading.value = false; return; }
  const code = selected.value;
  topologyLoading.value = true;
  try {
    const topology = await itsmApi.topology(code);
    if (requestId !== topologyRequest) return;
    topologyNodes.value = (topology.nodes as Record<string, unknown>[]) || [];
    topologyEdges.value = (topology.edges as Record<string, unknown>[]) || [];
  } catch (cause) {
    if (requestId === topologyRequest) {
      topologyError.value = cause instanceof Error ? cause.message : "依赖拓扑加载失败";
      throw cause;
    }
  } finally {
    if (requestId === topologyRequest) topologyLoading.value = false;
  }
}

async function selectCi(code: string) {
  if (code !== selected.value) {
    topologyNodes.value = [];
    topologyEdges.value = [];
  }
  selected.value = code;
  error.value = "";
  try { await loadTopology(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "依赖拓扑加载失败"; }
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
    await loadTopology();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "依赖关系保存失败";
  } finally { saving.value = false; }
}

onMounted(load);
onBeforeUnmount(() => {
  topologyRequest++;
});
</script>

<template>
  <div class="stack-page cmdb-page">
    <PageHeader :icon="Network" title="服务目录与依赖拓扑" description="找到服务，了解关联依赖与当前配置上下文"><template #actions><button class="button secondary" :disabled="loading || topologyLoading" @click="load"><RefreshCw :size="16" :class="{ 'motion-spin': loading }" />刷新目录</button><button v-if="auth.isAdmin" class="button primary" @click="openCi()"><Plus :size="16" />新增配置项</button></template></PageHeader>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
    <section class="itsm-split">
      <aside class="panel catalog-panel">
        <header class="panel-header"><div><h3>服务目录</h3><p>选择配置项查看依赖</p></div><span class="panel-count">{{ cis.length }}</span></header>
        <div class="cmdb-catalog-search"><div class="search-box compact"><Search :size="16" /><input v-model="keyword" aria-label="搜索服务目录" placeholder="搜索名称、编码或类型" /></div><small v-if="keyword">匹配 {{ filtered.length }} 个配置项</small></div>
        <div class="catalog-list">
        <button v-for="ci in filtered" :key="String(ci.ciCode)" class="catalog-item" :class="{ active: selected === ci.ciCode }" :aria-pressed="selected === ci.ciCode" @click="selectCi(String(ci.ciCode))">
          <span class="catalog-item-icon" :class="`ci-tone-${ciType(ci.ciType).tone}`"><component :is="ciType(ci.ciType).icon" :size="18" /></span><span class="catalog-item-copy"><strong>{{ ci.ciName }}</strong><small>{{ ci.ciCode }}</small><span class="catalog-item-meta"><span :title="String(ci.ciType)">{{ displayLabel(typeLabels, ci.ciType) }}</span><StatusBadge :value="String(ci.status)" /></span></span>
        </button>
        <LoadingState v-if="loading" text="正在读取服务目录…" compact />
        <EmptyState v-else-if="!filtered.length" :icon="Search" :title="keyword ? '没有匹配的配置项' : '服务目录暂无配置项'" :description="keyword ? '尝试其他名称、编码或类型。' : '添加配置项后可建立服务间的依赖关系。'" />
        </div>
      </aside>
      <section class="panel topology-panel">
        <header class="panel-header"><div><h3><Network :size="18" />{{ selectedCi?.ciName || "依赖拓扑" }}</h3><p><code>{{ selected || "请选择配置项" }}</code></p></div><div v-if="selectedCi" class="row-actions"><button class="button secondary" @click="inspectorOpen = true">详情</button><button v-if="auth.isAdmin" class="button secondary" @click="openCi(selectedCi)"><Pencil :size="15" />编辑</button><button v-if="auth.isAdmin" class="button secondary" @click="openRelation"><Link :size="15" />新增依赖</button></div></header>
        <CmdbTopologyGraph :root="selectedCi" :nodes="topologyNodes" :edges="topologyEdges" :loading="topologyLoading" :error="topologyError" @select="selectCi" />
      </section>
      <aside v-if="selectedCi" class="panel cmdb-inspector"><header class="panel-header"><div><h3>服务摘要</h3><p>当前选中配置项</p></div><span class="cmdb-summary-icon"><component :is="ciType(selectedCi.ciType).icon" :size="20" /></span></header><div class="cmdb-summary-identity"><strong>{{ selectedCi.ciName }}</strong><StatusBadge :value="String(selectedCi.status)" /></div><dl class="oa-definition-list"><div><dt>CI 编码</dt><dd><code>{{ selectedCi.ciCode }}</code></dd></div><div><dt>类型</dt><dd :title="String(selectedCi.ciType)">{{ displayLabel(typeLabels, selectedCi.ciType) }}</dd></div><div><dt>环境</dt><dd :title="String(selectedCi.environment)">{{ displayLabel(environmentLabels, selectedCi.environment) }}</dd></div><div><dt>负责人</dt><dd>{{ selectedCi.ownerName || '未设置' }}</dd></div><div><dt>访问地址</dt><dd><code>{{ selectedCi.endpoint || '未设置' }}</code></dd></div><div><dt>说明</dt><dd>{{ selectedCi.description || '暂无说明' }}</dd></div></dl></aside>
    </section>
    <DetailPanel v-if="inspectorOpen && selectedCi" title="配置项详情" :subtitle="String(selectedCi.ciCode)" @close="inspectorOpen = false"><dl class="oa-definition-list"><div><dt>名称</dt><dd>{{ selectedCi.ciName }}</dd></div><div><dt>类型</dt><dd :title="String(selectedCi.ciType)">{{ displayLabel(typeLabels, selectedCi.ciType) }}</dd></div><div><dt>环境</dt><dd :title="String(selectedCi.environment)">{{ displayLabel(environmentLabels, selectedCi.environment) }}</dd></div><div><dt>状态</dt><dd><StatusBadge :value="String(selectedCi.status)" /></dd></div><div><dt>负责人</dt><dd>{{ selectedCi.ownerName || '未设置' }}</dd></div><div><dt>Endpoint</dt><dd><code>{{ selectedCi.endpoint || '未设置' }}</code></dd></div><div><dt>说明</dt><dd>{{ selectedCi.description || '暂无说明' }}</dd></div></dl></DetailPanel>
    <BaseModal v-if="showCiForm" :title="editingId ? '编辑配置项' : '新增配置项'" @close="showCiForm = false"><form class="form-grid" @submit.prevent="saveCi"><label>CI 编码<input v-model.trim="ciForm.ciCode" required maxlength="64" /></label><label>名称<input v-model.trim="ciForm.ciName" required maxlength="128" /></label><label>类型<select v-model="ciForm.ciType"><option value="SERVICE">服务</option><option value="DATABASE">数据库</option><option value="CACHE">缓存</option><option value="QUEUE">消息队列</option><option value="GATEWAY">网关</option></select></label><label>环境<select v-model="ciForm.environment"><option value="PROD">生产</option><option value="STAGING">预发布</option><option value="TEST">测试</option><option value="DEV">开发</option></select></label><label>负责人<input v-model.trim="ciForm.ownerName" maxlength="64" /></label><label>状态<select v-model="ciForm.status"><option value="ACTIVE">正常</option><option value="INACTIVE">停用</option></select></label><label class="full">访问地址<input v-model.trim="ciForm.endpoint" maxlength="255" /></label><label class="full">说明<textarea v-model.trim="ciForm.description" rows="3" maxlength="500" /></label><p v-if="error" class="form-error full">{{ error }}</p><div class="form-actions full"><button type="button" class="button secondary" @click="showCiForm = false">取消</button><button class="button primary" :disabled="saving">{{ saving ? "保存中…" : "保存" }}</button></div></form></BaseModal>
    <BaseModal v-if="showRelationForm" title="新增服务依赖" @close="showRelationForm = false"><form class="form-grid" @submit.prevent="saveRelation"><label>源 CI<select v-model="relationForm.sourceCiCode"><option v-for="ci in cis" :key="String(ci.ciCode)" :value="ci.ciCode">{{ ci.ciName }}</option></select></label><label>目标 CI<select v-model="relationForm.targetCiCode" required><option value="" disabled>请选择</option><option v-for="ci in cis.filter((row) => row.ciCode !== relationForm.sourceCiCode)" :key="String(ci.ciCode)" :value="ci.ciCode">{{ ci.ciName }}</option></select></label><label>关系类型<select v-model="relationForm.relationType"><option v-for="(label, value) in relationLabels" :key="value" :value="value">{{ label }}</option></select></label><label>说明<input v-model.trim="relationForm.description" maxlength="500" /></label><p v-if="error" class="form-error full">{{ error }}</p><div class="form-actions full"><button type="button" class="button secondary" @click="showRelationForm = false">取消</button><button class="button primary" :disabled="saving">{{ saving ? "保存中…" : "保存依赖" }}</button></div></form></BaseModal>
  </div>
</template>
