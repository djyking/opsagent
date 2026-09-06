<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Database, FileCog, RefreshCw, ShieldCheck, History, ArrowUpRight, Search, LockKeyhole } from '@lucide/vue';
import ObservabilityWorkspaceView from './ObservabilityWorkspaceView.vue';
import InlineError from '@/components/InlineError.vue';
import LoadingState from '@/components/LoadingState.vue';
import EmptyState from '@/components/EmptyState.vue';
import BaseModal from '@/components/BaseModal.vue';
import { useConfigCenter } from '@/composables/useConfigCenter';
import '@/styles/pages/observability-governance.css';
const route = useRoute();
const ciCode = computed(() => typeof route.query.ciCode === 'string' ? route.query.ciCode : '');
const manager = useConfigCenter(() => ciCode.value);
const { catalog, detail, history, diff, selectedId, loading, detailLoading, error } = manager;
const keyword = ref(''); const source = ref('ALL'); const tab = ref<'content' | 'history'>('content');
const sources = computed(() => [...new Set(catalog.value?.items.map(i => i.source) || [])]);
const items = computed(() => (catalog.value?.items || []).filter(i => (source.value === 'ALL' || source.value === i.source) && `${i.name} ${i.serviceId} ${i.group}`.toLowerCase().includes(keyword.value.trim().toLowerCase())));
const sourceNames: Record<string, string> = { NACOS: 'Nacos', ENV: '环境变量', COMPOSE: '部署编排', LOCAL_FILE: '本地文件', DATABASE: '数据库', EXTERNAL: '外部服务', SECRET: '密钥' };
const state = (value?: string) => ({ AVAILABLE: '可读取', READ_ONLY: '部署管理', UNAVAILABLE: '暂不可读', DISABLED: '未启用', UNSUPPORTED: '尚未纳管', NOT_FOUND: '源不存在', FORBIDDEN: '源拒绝访问', UPSTREAM_UNAVAILABLE: '源暂不可用', INVALID_CONTENT: '内容无法安全解析' }[value || ''] || '待核对');
const capabilityNames = { canRead: '源读取', canDiff: '版本对比', canEdit: '白名单编辑', canPublish: '审批发布', canRollback: '受控回退', canVerifyApplied: '应用核验' } as const;
const selectedItem = computed(() => catalog.value?.items.find(item => item.id === selectedId.value));
function date(value?: string) { if (!value) return '—'; const parsed = new Date(/^\d+$/.test(value) ? Number(value) : value); return Number.isNaN(parsed.valueOf()) ? '—' : parsed.toLocaleString('zh-CN', { hour12: false }); }
onMounted(manager.load);
</script>
<template>
  <ObservabilityWorkspaceView title="服务与观测" description="看清配置来源与版本，让每次调整都有依据、可核对。">
    <template #actions><button class="button secondary" :disabled="loading" @click="manager.load"><RefreshCw :size="16" />刷新</button></template>
    <section class="gov-intro"><span class="gov-intro-icon"><FileCog :size="26" /></span><div><span class="gov-eyebrow">CONFIGURATION</span><h2>配置来源，一处可见。</h2><p>{{ ciCode ? `当前服务：${ciCode}` : '汇总 Nacos 与部署来源，核对安全预览、版本及变更入口。' }}</p></div><span class="gov-access"><ShieldCheck :size="16" />敏感值统一脱敏</span></section>
    <InlineError v-if="error" :message="error" />
    <p v-if="catalog" class="gov-note" :class="{ warning: catalog.status !== 'AVAILABLE' }">{{ catalog.message }}</p>
    <div class="gov-config-layout">
      <aside class="panel gov-directory" aria-label="配置目录">
        <header><h3>配置清单</h3><span>{{ items.length }} 项</span></header>
        <label class="gov-search"><Search :size="16" /><input v-model="keyword" placeholder="搜索配置或服务" aria-label="搜索配置" /></label>
        <select v-model="source" aria-label="配置来源"><option value="ALL">所有来源</option><option v-for="item in sources" :key="item" :value="item">{{ sourceNames[item] || item }}</option></select>
        <LoadingState v-if="loading && !catalog" text="正在读取配置目录…" />
        <div class="gov-directory-list"><button v-for="item in items" :key="item.id" :class="{ active: selectedId === item.id }" :aria-pressed="selectedId === item.id" @click="manager.select(item.id)"><Database v-if="item.source === 'NACOS'" :size="18" /><LockKeyhole v-else :size="18" /><span><strong>{{ item.name }}</strong><small>{{ sourceNames[item.source] }} · {{ item.serviceId }}</small></span></button></div>
        <p v-if="catalog && !items.length" class="gov-empty-text">没有符合当前条件的配置。{{ catalog.status === 'AVAILABLE' ? '' : 'Nacos 来源暂不可读。' }}</p>
      </aside>
      <section class="panel gov-detail" aria-label="配置详情">
        <LoadingState v-if="detailLoading" :text="`正在读取 ${selectedItem?.name || '所选配置'} 的独立源内容与历史…`" />
        <EmptyState v-else-if="!detail" :icon="FileCog" title="选择一项配置" description="查看来源、脱敏内容与真实版本记录。" />
        <template v-else>
          <header class="gov-section-heading"><div><h3>{{ detail.item.name }}</h3><p>{{ detail.item.description }}</p></div><span class="gov-badge">{{ state(detail.status) }}</span></header>
          <dl class="gov-facts"><div><dt>来源</dt><dd>{{ sourceNames[detail.item.source] }}</dd></div><div><dt>命名空间 / 分组</dt><dd>{{ detail.item.namespace || '—' }} / {{ detail.item.group || '—' }}</dd></div><div><dt>版本摘要</dt><dd><code :title="detail.revision">{{ detail.revision?.slice(0, 12) || '—' }}</code></dd></div><div><dt>最近核对</dt><dd>{{ date(detail.observedAt) }}</dd></div></dl>
          <section v-if="detail.item.identity" class="gov-config-identity" aria-label="完整配置身份"><dl><div><dt>源实例 / 环境</dt><dd>{{ detail.item.identity.sourceInstanceId }} / {{ detail.item.identity.environment }}</dd></div><div><dt>源路径</dt><dd>{{ detail.item.identity.namespaceId || '—' }} / {{ detail.item.identity.group || '—' }} / {{ detail.item.identity.dataId }}</dd></div><div><dt>目标范围</dt><dd>{{ detail.item.identity.targetScope.join('、') }}<span v-if="detail.item.shared"> · 共享源，变更影响上述全部目标</span></dd></div></dl><div class="gov-config-capabilities"><span v-for="(label, key) in capabilityNames" :key="key" :class="{ enabled: detail.item.capabilities[key] }">{{ detail.item.capabilities[key] ? '✓' : '—' }} {{ label }}</span></div><p>{{ detail.item.capabilities.reasons.publish }}；{{ detail.item.capabilities.reasons.verifyApplied }}。</p></section>
          <nav class="gov-local-tabs" aria-label="配置详情视图"><button :class="{ active: tab === 'content' }" @click="tab = 'content'"><FileCog :size="16" />配置预览</button><button :class="{ active: tab === 'history' }" @click="tab = 'history'"><History :size="16" />版本记录</button></nav>
          <div class="gov-detail-body">
            <template v-if="tab === 'content'"><p class="gov-note">{{ detail.message }}</p><pre v-if="detail.content" class="gov-code">{{ detail.content }}</pre><EmptyState v-else :icon="LockKeyhole" :title="detail.status === 'READ_ONLY' ? '配置由部署系统管理' : '配置正文暂不可读'" :description="detail.item.description" /></template>
            <template v-else><p class="gov-note">{{ history?.message }}</p><article v-for="version in history?.items" :key="version.id" class="gov-version"><div><strong>版本 #{{ version.id }}</strong><p>{{ version.actor || 'Nacos' }} · {{ date(version.modifiedAt) }}</p></div><button class="button secondary small" @click="manager.compare(version.id)">与当前版本比较</button></article><EmptyState v-if="!history?.items.length" :icon="History" :title="history?.status === 'UNAVAILABLE' ? '版本记录暂不可读' : '暂无可展示的版本记录'" description="只展示 Nacos 实际返回的版本，不生成示例历史。" /></template>
            <RouterLink v-if="detail.item.managementPath" :to="detail.item.managementPath" class="button primary gov-managed-link"><ArrowUpRight :size="16" />{{ detail.item.capabilities?.canEdit ? '申请变更与受控回退' : '查看关联治理与实际应用' }}</RouterLink>
          </div>
        </template>
      </section>
    </div>
    <BaseModal v-if="diff" title="配置版本差异" :description="diff.message" wide @close="diff = undefined"><div v-if="diff.status === 'AVAILABLE'" class="gov-diff"><section><h3>历史版本 #{{ diff.versionId }}</h3><pre class="gov-code">{{ diff.previousContent }}</pre></section><section><h3>当前版本</h3><pre class="gov-code">{{ diff.currentContent }}</pre></section></div><p v-else class="gov-note warning">{{ diff.message }}</p></BaseModal>
  </ObservabilityWorkspaceView>
</template>
