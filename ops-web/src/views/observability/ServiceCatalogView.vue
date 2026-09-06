<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { List, Plus, Search, RefreshCw } from '@lucide/vue';
import ObservabilityWorkspaceView from './ObservabilityWorkspaceView.vue';
import ServiceNodeDrawer from '@/components/observability/ServiceNodeDrawer.vue';
import ServiceEditor from '@/components/observability/ServiceEditor.vue';
import ServiceHealthBadge from '@/components/observability/ServiceHealthBadge.vue';
import PaginationBar from '@/components/PaginationBar.vue';
import InlineError from '@/components/InlineError.vue';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';
import { useObservabilityStore } from '@/stores/observability';
import { useAuthStore } from '@/stores/auth';
import type { ServiceNode } from '@/api/observability';
import { ciType, environmentNames } from '@/components/cmdb/topology';
import { effectiveHealth, observationLabels, serviceContext } from '@/utils/observability';
import { displayEndpoint } from '@/utils/service-endpoint';
const store = useObservabilityStore(); const auth = useAuthStore(); const route = useRoute(); const router = useRouter();
const page = ref(1); const search = ref(''); const drawer = ref(false); const editor = ref(false); const editingNode = ref<ServiceNode>();
const filtered = computed(() => [...(store.topologyData?.nodes || [])].filter(node => !node.virtual).filter(node => `${node.ciName} ${node.ciCode} ${node.ownerName || ''} ${node.ciType}`.toLowerCase().includes(search.value.trim().toLowerCase())).sort((a, b) => Number(!/rabbitmq/i.test(`${a.ciCode} ${a.ciName}`)) - Number(!/rabbitmq/i.test(`${b.ciCode} ${b.ciName}`)) || a.ciName.localeCompare(b.ciName, 'zh-CN')));
const rows = computed(() => filtered.value.slice((page.value - 1) * 12, page.value * 12));
function select(node: ServiceNode) { store.selectedCiCode = node.ciCode; drawer.value = true; void router.replace({ query: serviceContext(node.ciCode, store.selectedEnvironment, store.timeRange) }); }
function edit(node?: ServiceNode) { editingNode.value = node; editor.value = true; }
async function saved() { editor.value = false; await store.load(); }
watch(search, () => { page.value = 1; });
watch(() => store.selectedEnvironment, () => { page.value = 1; void router.replace({ query: serviceContext(store.selectedCiCode, store.selectedEnvironment, store.timeRange) }); void store.load(); });
watch(() => auth.token, () => { drawer.value = false; editor.value = false; if (auth.token) void store.load(); });
onMounted(() => { if (route.query.environment) store.selectedEnvironment = String(route.query.environment); if (route.query.ciCode) store.selectedCiCode = String(route.query.ciCode); void store.load(); });
onBeforeUnmount(() => store.invalidate());
</script>
<template><ObservabilityWorkspaceView description="服务台账保留唯一事实源，运行健康由实际观测补充。"><template #actions><button class="button secondary" :disabled="store.loading" @click="store.load"><RefreshCw :size="16" />刷新</button><button v-if="auth.isAdmin" class="button primary" @click="edit()"><Plus :size="16" />登记服务</button></template><InlineError v-if="store.error" :message="store.error" /><section class="panel obs-catalog"><div class="obs-toolbar"><div class="search-box obs-service-search"><Search :size="16" /><input v-model="search" placeholder="搜索服务、类型、负责人" aria-label="搜索服务目录" /></div><label>环境<select v-model="store.selectedEnvironment"><option value="ALL">全部环境</option><option v-for="(label, key) in environmentNames" :key="key" :value="key">{{ label }}</option></select></label><span class="obs-muted">{{ filtered.length }} 项 · RabbitMQ 优先展示</span></div><LoadingState v-if="store.loading && !store.topologyData" text="读取服务目录…" /><div v-else-if="rows.length" class="obs-table-scroll"><table class="obs-table"><thead><tr><th>服务 / CI 编码</th><th>类型 / 环境</th><th>负责人</th><th>运行状态</th><th>地址</th><th>操作</th></tr></thead><tbody><tr v-for="node in rows" :key="node.ciCode"><td><button class="obs-service-link" @click="select(node)"><span class="obs-type-icon"><component :is="ciType(node.ciType).icon" :size="20" /></span><span><strong>{{ node.ciName }}</strong><small>{{ node.ciCode }}</small></span></button></td><td>{{ ciType(node.ciType).label }}<small>{{ environmentNames[node.environment] || node.environment }}</small></td><td>{{ node.ownerName || '未登记' }}</td><td><ServiceHealthBadge :health="effectiveHealth(node)" :reason="node.statusReason" /><small v-if="node.observation" class="obs-inspection-result" :title="node.observation.message">{{ observationLabels[node.observation.status] }}</small></td><td><code class="obs-endpoint">{{ displayEndpoint(node.endpoint) }}</code></td><td><div class="row-actions"><button class="text-button" @click="select(node)">详情</button><RouterLink class="text-button" :to="{ path: '/observability/topology', query: serviceContext(node.ciCode, store.selectedEnvironment, store.timeRange) }">拓扑</RouterLink><button v-if="auth.isAdmin" class="text-button" @click="edit(node)">编辑</button></div></td></tr></tbody></table></div><EmptyState v-else :icon="List" title="暂无匹配服务" description="调整搜索条件，或由管理员登记服务节点。" /><PaginationBar v-if="filtered.length" :page="page" :page-size="12" :total="filtered.length" @change="page = $event" /></section><ServiceNodeDrawer v-if="drawer && store.selectedNode" :node="store.selectedNode" :environment="store.selectedEnvironment" :time-range="store.timeRange" @close="drawer = false" /><ServiceEditor v-if="editor && auth.isAdmin" :node="editingNode" @close="editor = false" @saved="saved" /></ObservabilityWorkspaceView></template>
