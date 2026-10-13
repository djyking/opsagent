<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Database, FileJson, History, LockKeyhole, RefreshCw, RotateCcw, Settings2, ShieldCheck } from '@lucide/vue';
import PageHeader from '@/components/PageHeader.vue';
import FormField from '@/components/FormField.vue';
import InlineError from '@/components/InlineError.vue';
import LoadingState from '@/components/LoadingState.vue';
import EmptyState from '@/components/EmptyState.vue';
import BaseModal from '@/components/BaseModal.vue';
import PaginationBar from '@/components/PaginationBar.vue';
import { useAuthStore } from '@/stores/auth';
import { useManagedConfiguration } from '@/composables/useManagedConfiguration';
import type { ManagedConfigurationId } from '@/api/configuration';
import '@/styles/pages/configuration.css';

const auth = useAuthStore();
const router = useRouter();
const manager = useManagedConfiguration(() => auth.isAdmin, () => auth.user?.userId, runId => { void router.push({ path: '/automation', query: { tab: 'runs', run: runId } }); });
const { items, selectedId, detail, draft, history, loading, historyLoading, busy, error, historyError, notice,
  plan, comment, dirty, writable, canPublish } = manager;
const tab = ref<'content' | 'history'>('content');
const destination = ref<ManagedConfigurationId>();
watch([() => auth.user?.userId, () => auth.isAdmin], () => { destination.value = undefined; }, { flush: 'sync' });
const actionLabel = computed(() => plan.value?.versionId == null ? '提交发布审批' : '提交回退审批');
const targetApplied = computed(() => detail.value?.applicationStatus === 'APPLIED'
  && !!detail.value.revision && detail.value.revision === detail.value.appliedRevision);
const businessApplied = computed(() => targetApplied.value && detail.value?.business?.httpStatus === 200
  && detail.value.business.businessConfigurationRevision === detail.value.revision);
const applicationLabel = computed(() => targetApplied.value ? '目标已应用'
  : detail.value?.applicationStatus === 'APPLIED' ? '目标应用待确认' : status(detail.value?.applicationStatus));
const statuses: Record<string, string> = { APPLIED: '已应用', READY: '已连接', CONNECTED: '已连接', AVAILABLE: '可读取',
  HEALTHY: '连接正常', OK: '正常', REQUESTED: '已提交，待确认', UNCONFIRMED: '尚未确认', REJECTED: '已拒绝',
  UNAVAILABLE: '暂不可用', UNKNOWN: '尚未确认', DISABLED: '未启用', INITIALIZING: '初始化中', READ_ONLY: '只读',
  NACOS_CONFIRMATION_PENDING: '等待 Nacos 确认', INVALID_CONFIGURATION: '配置内容异常' };
function status(value?: string) { return value ? statuses[value] || '等待核对' : '尚未读取'; }
function date(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; }
function price(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? `¥ ${value.toFixed(2)}` : '—'; }
function select(id: ManagedConfigurationId) {
  if (busy.value) return;
  if (dirty.value) { destination.value = id; return; }
  tab.value = 'content'; void manager.select(id);
}
function discardAndSelect() { const id = destination.value; destination.value = undefined; if (id) { tab.value = 'content'; void manager.select(id); } }
onMounted(manager.load);
onBeforeUnmount(manager.dispose);
</script>

<template>
  <div class="stack-page configuration-page">
    <PageHeader title="配置中心" eyebrow="NACOS CONFIGURATION" :icon="Settings2" description="查看配置、核对变更与生效状态，让每次调整都有版本可追溯。">
      <template #actions><button class="button secondary" :disabled="loading || busy" @click="select(selectedId)"><RefreshCw :size="16" />刷新配置</button></template>
    </PageHeader>
    <section class="configuration-intro">
      <span class="configuration-intro-icon"><Database :size="25" /></span>
      <div><h3>隔离订单 · 受控变更</h3><p>编辑白名单字段，核对差异后提交审批。</p></div>
      <span class="configuration-access"><ShieldCheck :size="16" />{{ auth.isAdmin ? '管理员可提交变更审批' : '当前账号只读浏览' }}</span>
    </section>
    <InlineError v-if="error" :message="error" />
    <p v-if="notice" class="configuration-notice" role="status">{{ notice }}</p>
    <div class="configuration-workspace">
      <aside class="configuration-directory" aria-label="纳管配置">
        <header><h3>配置清单</h3><span>{{ items.length }} 项</span></header>
        <button v-for="item in items" :key="item.id" class="configuration-entry" :class="{ active: selectedId === item.id }" :disabled="busy" :aria-pressed="selectedId === item.id" @click="select(item.id)">
          <FileJson :size="20" /><span><strong>{{ item.name }}</strong><small>{{ item.description }}</small><span class="configuration-entry-mode">{{ item.editable ? '业务配置' : '演练管理 · 只读' }}</span></span>
        </button>
        <p class="configuration-directory-note">配置发布与演练互斥。演练中的连接和限流规则由原审批流程管理。</p>
      </aside>
      <section class="configuration-main" aria-label="配置详情">
        <LoadingState v-if="loading && !detail" text="正在核对 Nacos 配置与目标服务…" />
        <EmptyState v-else-if="!detail" title="配置尚未读取" description="请刷新配置；连接异常时不会显示过期的成功状态。" :icon="FileJson" />
        <template v-else>
          <section class="configuration-card">
            <header class="configuration-card-header"><div><h3>{{ detail.name }}</h3><p>{{ detail.group }} · {{ detail.dataId }}</p></div><span class="configuration-state" :class="{ applied: targetApplied }">{{ applicationLabel }}</span></header>
            <dl class="configuration-facts">
              <div><dt>Nacos 状态</dt><dd>{{ status(detail.nacosStatus) }}</dd></div>
              <div><dt>当前版本</dt><dd><code :title="detail.revision">{{ detail.revision?.slice(0, 12) || '—' }}</code></dd></div>
              <div><dt>目标应用版本</dt><dd><code :title="detail.appliedRevision">{{ detail.appliedRevision?.slice(0, 12) || '—' }}</code></dd></div>
              <div><dt>最近核对</dt><dd>{{ date(detail.observedAt) }}</dd></div>
            </dl>
            <div class="configuration-tabs" role="tablist" aria-label="配置内容与版本">
              <button role="tab" :aria-selected="tab === 'content'" :class="{ active: tab === 'content' }" @click="tab = 'content'"><FileJson :size="16" />配置内容</button>
              <button role="tab" :aria-selected="tab === 'history'" :class="{ active: tab === 'history' }" @click="tab = 'history'"><History :size="16" />版本记录</button>
            </div>
            <div v-if="tab === 'content'" class="configuration-card-body" role="tabpanel">
              <p v-if="!detail.editable" class="configuration-context"><LockKeyhole :size="17" />运行配置由隔离演练管理。<RouterLink to="/automation">前往 AI 自动化</RouterLink></p>
              <template v-if="detail.editable">
                <p v-if="detail.blockedReason" class="configuration-notice">{{ detail.blockedReason }}</p>
                <form class="configuration-editor" @submit.prevent="manager.prepare()">
                  <div class="configuration-editor-grid">
                    <FormField label="订单目录标题" help="实际业务预览返回的标题，最多 60 字。"><input v-model="draft.catalogTitle" maxlength="60" required :readonly="!writable" :disabled="busy" /></FormField>
                    <FormField label="演示折扣" help="0 至 30 的整数；只影响隔离订单的示例报价。"><div class="configuration-percent"><input v-model.number="draft.discountPercent" type="number" min="0" max="30" step="1" required :readonly="!writable" :disabled="busy" /><span>%</span></div></FormField>
                    <FormField class="configuration-editor-wide" label="业务提示" help="最多 160 字；空白表示不显示附加提示。"><textarea v-model="draft.notice" rows="3" maxlength="160" :readonly="!writable" :disabled="busy"></textarea></FormField>
                  </div>
                  <div class="configuration-editor-footer"><span>{{ dirty ? '有尚未发布的修改' : '当前内容与已读取配置一致' }}</span><div v-if="auth.isAdmin"><button type="button" class="button secondary" :disabled="!dirty || busy" @click="manager.resetDraft">还原编辑</button><button type="submit" class="button primary" :disabled="!canPublish">{{ busy ? '正在核对…' : '校验并预览变更' }}</button></div></div>
                </form>
              </template>
              <details class="configuration-json"><summary>{{ detail.editable ? '查看当前配置 JSON' : '查看演练运行配置' }}</summary><pre>{{ JSON.stringify(detail.content, null, 2) }}</pre></details>
              <details class="configuration-json"><summary>发布与目标应用证据</summary><p>源发布、目标采用和业务实际读取分别核对；这里仅覆盖隔离订单的固定目标实例。</p><dl class="configuration-facts"><div><dt>目标实例</dt><dd>{{ detail.application?.instanceId || '未取得实例标识' }}</dd></div><div><dt>目标采样时间</dt><dd>{{ date(detail.application?.observedAt) }}</dd></div><div><dt>业务采用当前版本</dt><dd>{{ businessApplied ? '已核实' : '尚未确认' }}</dd></div><div><dt>目标配置同步</dt><dd>{{ detail.application?.applicationPause?.active ? '当前暂停，源发布不代表目标已应用' : '以目标版本及采样结果为准' }}</dd></div></dl></details>
            </div>
            <div v-else class="configuration-history" role="tabpanel">
              <InlineError v-if="historyError" :message="historyError" />
              <LoadingState v-if="historyLoading" text="正在读取配置版本…" />
              <EmptyState v-else-if="!history.items.length" title="暂无发布记录" :description="detail.editable ? '首次发布后会保存变更内容、操作者和应用结果。' : '演练运行配置的变更记录保存在 AI 自动化的执行轨迹中。'" :icon="History" />
              <template v-else><article v-for="version in history.items" :key="version.id" class="configuration-version">
                <header><div><strong>版本 #{{ version.version }}</strong><span>{{ version.action === 'BASELINE' ? '初始基线' : version.action === 'ROLLBACK' ? '回退发布' : '配置发布' }}</span><span class="configuration-state" :class="{ applied: version.status === 'APPLIED' }">{{ status(version.status) }}</span></div><button v-if="auth.isAdmin && detail.editable" class="button secondary small" :disabled="!writable || busy || version.status !== 'APPLIED'" @click="manager.prepare(version)"><RotateCcw :size="14" />回退到此版本</button></header>
                <p>{{ version.comment }}</p><small>{{ version.actorName }} · {{ date(version.createdAt) }}</small>
                <p v-if="version.message" class="configuration-version-message">{{ version.message }}</p>
                <details class="configuration-json"><summary>查看该版本内容</summary><pre>{{ JSON.stringify(version.content, null, 2) }}</pre></details>
              </article><PaginationBar :page="history.page" :page-size="history.size" :total="history.total" @change="manager.loadHistory" /></template>
            </div>
          </section>
          <details class="configuration-card configuration-business">
            <summary class="configuration-card-header"><div><h3>目标业务实际读取</h3><p>展开核对实际报价与应用结果。</p></div><span class="configuration-state" :class="{ applied: businessApplied }">{{ businessApplied ? '业务已采用当前版本' : detail.business?.httpStatus ? `HTTP ${detail.business.httpStatus} · 版本待确认` : '尚未读取' }}</span></summary>
            <div class="configuration-card-body"><div class="configuration-quote"><div><span>目录标题</span><strong>{{ detail.business?.catalogTitle || '—' }}</strong><p>{{ detail.business?.notice || '暂无附加提示' }}</p></div><dl><div><dt>基础金额</dt><dd>{{ price(detail.business?.basePrice) }}</dd></div><div><dt>当前报价</dt><dd>{{ price(detail.business?.quotedPrice) }}</dd></div></dl></div></div>
          </details>
        </template>
      </section>
    </div>
    <BaseModal v-if="plan" :title="actionLabel" description="请核对具体变化。提交将创建变更提案，经 AI 自动化审批批准后才写入 Nacos，并核验目标服务是否应用。" wide @close="!busy && (plan = undefined)">
      <div class="configuration-confirm"><p>目标：隔离订单服务 · {{ detail?.dataId }}</p><p v-if="plan.versionId != null">以版本 #{{ plan.versionNumber }} 的内容创建新的发布版本，保留已有历史。</p>
        <table><thead><tr><th>配置项</th><th>当前值</th><th>发布后</th></tr></thead><tbody><tr v-for="change in plan.changes" :key="change.key"><th>{{ change.label }}</th><td>{{ change.before }}</td><td>{{ change.after }}</td></tr></tbody></table>
        <FormField label="变更说明" help="必填，最多 500 字；与操作者及发布结果一同保存。"><textarea v-model="comment" rows="3" maxlength="500" :disabled="busy" placeholder="说明此次调整的原因与预期效果…"></textarea></FormField>
      </div>
      <template #footer><button class="button secondary" :disabled="busy" @click="plan = undefined">返回编辑</button><button class="button primary" :disabled="busy || !comment.trim() || comment.trim().length > 500" @click="manager.confirm">{{ busy ? '正在提交并核对…' : `确认${actionLabel}` }}</button></template>
    </BaseModal>
    <BaseModal v-if="destination" title="放弃尚未发布的修改？" description="重新读取配置会替换当前编辑内容，已发布版本不受影响。" @close="destination = undefined">
      <template #footer><button class="button secondary" @click="destination = undefined">继续编辑</button><button class="button primary" @click="discardAndSelect">放弃修改并读取</button></template>
    </BaseModal>
  </div>
</template>
