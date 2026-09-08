<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Database, FileCog, RefreshCw, ArrowUpRight, Search, LockKeyhole } from '@lucide/vue';
import ObservabilityWorkspaceView from './ObservabilityWorkspaceView.vue';
import InlineError from '@/components/InlineError.vue';
import LoadingState from '@/components/LoadingState.vue';
import EmptyState from '@/components/EmptyState.vue';
import BaseModal from '@/components/BaseModal.vue';
import ConfigurationFiles from '@/components/configuration/ConfigurationFiles.vue';
import { useConfigCenter } from '@/composables/useConfigCenter';
import '@/styles/pages/observability-governance.css';
import '@/styles/pages/config-center.css';

const route = useRoute();
const ciCode = computed(() => typeof route.query.ciCode === 'string' ? route.query.ciCode : '');
const manager = useConfigCenter(() => ciCode.value);
const { catalog, detail, history, diff, selectedId, loading, detailLoading, historyLoading, error } = manager;
const keyword = ref(''); const source = ref('ALL');
const sources = computed(() => [...new Set(catalog.value?.items.map(i => i.source) || [])]);
const items = computed(() => (catalog.value?.items || []).filter(i => (source.value === 'ALL' || source.value === i.source) && [i.name, i.serviceId, i.group].join(' ').toLowerCase().includes(keyword.value.trim().toLowerCase())));
const fields = computed(() => detail.value?.overview?.fields || []);
const primaryFields = computed(() => fields.value.slice(0, 7));
const moreFields = computed(() => fields.value.slice(7));
const runtimeAvailable = computed(() => detail.value?.overview?.status === 'AVAILABLE');
const sourceNames: Record<string, string> = { NACOS: 'Nacos', ENV: '环境变量', COMPOSE: '部署编排', LOCAL_FILE: '本地文件', DATABASE: '数据库', EXTERNAL: '外部服务', SECRET: '密钥' };
const states: Record<string, string> = { AVAILABLE: '已读取', FORBIDDEN: '无读取权限', UNSUPPORTED: '未接入', UNAVAILABLE: '读取失败', UPSTREAM_UNAVAILABLE: '来源不可用', INVALID_CONTENT: '内容无法核实', NOT_FOUND: '源不存在', STALE: '快照已过期', SHARED_SOURCE: '多个目标待核对' };
const state = (value?: string) => states[value || ''] || '待核对';
function date(value?: string | null) { if (!value) return '—'; const parsed = new Date(/^\d+$/.test(value) ? Number(value) : value); return Number.isNaN(parsed.valueOf()) ? '—' : parsed.toLocaleString('zh-CN', { hour12: false }); }
function historyToggled(event: Event) { if ((event.target as HTMLDetailsElement).open) void manager.loadHistory(); }
const markerOnly = computed(() => {
  try {
    const value = JSON.parse(detail.value?.content || '{}');
    return !!(value.info?.middleware?.['nacos-config'] || value['info.middleware.nacos-config']);
  } catch { return false; }
});
onMounted(manager.load);
</script>

<template>
  <ObservabilityWorkspaceView title="服务与观测" description="查看服务关键配置，核对来源与实际读取结果。">
    <template #actions><button class="button secondary" :disabled="loading || detailLoading" @click="manager.load"><RefreshCw :size="16" />刷新配置</button></template>
    <ConfigurationFiles :ci-code="ciCode" /><details class="panel cc-existing-sources"><summary>其他配置来源与旧版运行快照</summary><div class="cc-existing-body">
    <InlineError v-if="error" :message="error" />
    <p v-if="catalog && catalog.status !== 'AVAILABLE'" class="gov-note warning">{{ catalog.message }}</p>
    <div class="gov-config-layout cc-workspace">
      <aside class="panel gov-directory" aria-label="配置目录">
        <header><h3>配置清单</h3><span>{{ items.length }} 项</span></header>
        <label class="gov-search"><Search :size="16" /><input v-model="keyword" placeholder="搜索配置或服务" aria-label="搜索配置" /></label>
        <select v-model="source" aria-label="配置来源"><option value="ALL">所有来源</option><option v-for="item in sources" :key="item" :value="item">{{ sourceNames[item] || item }}</option></select>
        <LoadingState v-if="loading && !catalog" text="正在读取配置目录…" />
        <div class="gov-directory-list"><button v-for="item in items" :key="item.id" :class="{ active: selectedId === item.id }" :aria-pressed="selectedId === item.id" @click="manager.select(item.id)"><Database v-if="item.source === 'NACOS'" :size="18" /><LockKeyhole v-else :size="18" /><span><strong>{{ item.name }}</strong><small>{{ sourceNames[item.source] }} · {{ item.serviceId }}</small></span></button></div>
        <p v-if="catalog && !items.length" class="gov-empty-text">没有符合当前条件的配置。</p>
      </aside>

      <section class="panel gov-detail cc-detail" aria-label="配置详情">
        <LoadingState v-if="detailLoading" text="正在核对服务配置与来源…" />
        <EmptyState v-else-if="!detail" :icon="FileCog" title="选择一项配置" description="查看关键参数、真实来源与读取证据。" />
        <template v-else>
          <header class="cc-detail-heading"><div><span class="cc-kicker">服务关键配置</span><h3>{{ detail.overview?.serviceId || detail.item.serviceId }}</h3><p>{{ detail.item.name }} · {{ sourceNames[detail.item.source] }}</p></div><span class="cc-status" :class="{ ready: runtimeAvailable }">{{ runtimeAvailable ? '已取得运行快照' : '运行值' + state(detail.overview?.status) }}</span></header>
          <div class="cc-main">
            <div class="cc-field-heading"><strong>{{ runtimeAvailable ? '目标实例当前解析值' : fields.length ? '已读取的源参数' : '关键参数待核实' }}</strong><small>{{ runtimeAvailable ? date(detail.overview?.observedAt) : '未标为已生效' }}</small></div>
            <p class="cc-subtle">{{ runtimeAvailable ? '这里只确认配置解析结果；连接状态和业务是否应用需查看运行证据。' : detail.overview?.message || '当前服务尚未接入安全运行快照，原文可按需查看。' }}</p>
            <div v-if="primaryFields.length" class="cc-table-scroll"><table class="cc-fields"><thead><tr><th>配置项</th><th>当前读取值</th><th>来源</th></tr></thead><tbody><tr v-for="field in primaryFields" :key="field.key"><th scope="row"><span>{{ field.label }}</span><code>{{ field.key }}</code></th><td><code>{{ field.value }}</code><small v-if="field.verification === 'SOURCE_ONLY'">仅源配置 · 未核实采用</small></td><td>{{ field.source }}</td></tr></tbody></table></div>
            <div v-else class="cc-unavailable"><FileCog :size="28" /><p>没有取得可安全展示的关键参数。</p><button class="button secondary small" @click="manager.select(selectedId)"><RefreshCw :size="14" />重新核对</button></div>
            <RouterLink v-if="detail.item.managementPath" :to="detail.item.managementPath" class="button primary cc-change"><ArrowUpRight :size="16" />{{ detail.item.capabilities.canEdit ? '创建受控变更' : '查看专用管理' }}</RouterLink>
          </div>

          <div :key="selectedId" class="cc-accordions">
            <details v-if="moreFields.length" class="cc-fold"><summary><span>其余关键参数</span><small>{{ moreFields.length }} 项</small></summary><div class="cc-fold-body cc-table-scroll"><table class="cc-fields"><thead><tr><th>配置项</th><th>当前读取值</th><th>来源</th></tr></thead><tbody><tr v-for="field in moreFields" :key="field.key"><th scope="row"><span>{{ field.label }}</span><code>{{ field.key }}</code></th><td><code>{{ field.value }}</code><small v-if="field.verification === 'SOURCE_ONLY'">仅源配置 · 未核实采用</small></td><td>{{ field.source }}</td></tr></tbody></table></div></details>
            <details class="cc-fold"><summary><span>运行证据与读取边界</span><small>{{ runtimeAvailable ? '单个目标实例' : state(detail.overview?.status) }}</small></summary><div class="cc-fold-body"><p>{{ detail.overview?.message || '没有运行快照。' }}</p><dl class="cc-evidence"><div><dt>目标服务</dt><dd>{{ detail.overview?.serviceId || detail.item.serviceId }}</dd></div><div><dt>采样时间</dt><dd>{{ date(detail.overview?.observedAt) }}</dd></div><div><dt>进程标识</dt><dd><code>{{ detail.overview?.instanceId || '—' }}</code></dd></div><div><dt>业务应用确认</dt><dd>{{ detail.item.capabilities.canVerifyApplied ? '进入专用管理核对目标实际应用结果' : '未接入业务应用反馈' }}</dd></div></dl><p>此快照只读取固定非敏感参数；未展示的项不代表未配置。属性被解析也不代表已有连接池、客户端或其他对象已重新加载。</p></div></details>
            <details class="cc-fold"><summary><span>源配置与完整身份</span><small>{{ sourceNames[detail.item.source] }} · {{ state(detail.status) }}</small></summary><div class="cc-fold-body"><p>{{ detail.message }}</p><p v-if="markerOnly" class="gov-note">该源包含接入标记。<code>connected</code> 是静态配置文字，不能证明实时连接正常；不同服务源内容可以相同。</p><dl class="cc-evidence"><div><dt>环境 / 来源实例</dt><dd>{{ detail.item.identity.environment }} / {{ detail.item.identity.sourceInstanceId }}</dd></div><div><dt>命名空间 / 分组</dt><dd>{{ detail.item.namespace || '—' }} / {{ detail.item.group || '—' }}</dd></div><div><dt>Data ID</dt><dd>{{ detail.item.dataId }}</dd></div><div><dt>版本摘要</dt><dd><code :title="detail.revision">{{ detail.revision?.slice(0, 12) || '—' }}</code></dd></div><div><dt>关联服务</dt><dd>{{ detail.item.identity.targetScope.join('、') }}</dd></div><div><dt>源读取时间</dt><dd>{{ date(detail.observedAt) }}</dd></div></dl><pre v-if="detail.content" class="gov-code">{{ detail.content }}</pre><p v-else>此来源暂无可展示的正文快照。</p></div></details>
            <details class="cc-fold" @toggle="historyToggled"><summary><span>版本与变更记录</span><small>按需读取</small></summary><div class="cc-fold-body"><LoadingState v-if="historyLoading" text="正在读取版本记录…" /><template v-else><p>{{ history?.message }}</p><article v-for="version in history?.items" :key="version.id" class="gov-version"><div><strong>版本 #{{ version.id }}</strong><p>{{ version.actor || 'Nacos' }} · {{ date(version.modifiedAt) }}</p></div><button class="button secondary small" @click="manager.compare(version.id)">与当前版本比较</button></article><p v-if="history && !history.items.length">暂无可展示的版本记录。</p><button v-if="history?.status === 'UNAVAILABLE'" class="button secondary small" @click="manager.loadHistory">重新读取</button></template></div></details>
            <details class="cc-fold"><summary><span>修改与发布能力</span><small>{{ detail.item.capabilities.canEdit ? '白名单变更' : '只读' }}</small></summary><div class="cc-fold-body"><p>{{ detail.item.capabilities.reasons.edit }}</p><p>可编辑范围保持现有白名单：隔离订单的目录标题、业务提示、演示折扣；限流规则使用专用管理。此区域保留旧来源的读取能力；实际应用参数请使用上方受控文件管理。</p><p>变更草稿核对差异和基线版本后，沿现有审批发布；发布成功与目标已应用分别核对。</p></div></details>
          </div>
        </template>
      </section>
    </div>
    </div></details>
    <BaseModal v-if="diff" title="配置版本差异" :description="diff.message" wide @close="diff = undefined"><div v-if="diff.status === 'AVAILABLE'" class="gov-diff"><section><h3>历史版本 #{{ diff.versionId }}</h3><pre class="gov-code">{{ diff.previousContent }}</pre></section><section><h3>当前版本</h3><pre class="gov-code">{{ diff.currentContent }}</pre></section></div><p v-else class="gov-note warning">{{ diff.message }}</p></BaseModal>
  </ObservabilityWorkspaceView>
</template>
