<script setup lang="ts">
import { AlertTriangle, CheckCircle2, Layers3, Palette, Server } from "@lucide/vue";
import PageHeader from "@/components/PageHeader.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import PriorityIndicator from "@/components/PriorityIndicator.vue";
import ListSurface from "@/components/ListSurface.vue";
import FormField from "@/components/FormField.vue";
import DescriptionList from "@/components/DescriptionList.vue";
import TechnicalMetadata from "@/components/TechnicalMetadata.vue";
import EmptyState from "@/components/EmptyState.vue";
import { computed, onBeforeUnmount, ref } from "vue";
import { useReducedMotion } from "@/composables/useReducedMotion";
import { useToast } from "@/composables/useToast";
import BaseModal from "@/components/BaseModal.vue";
import DetailPanel from "@/components/DetailPanel.vue";
import ActionButton from "@/components/feedback/ActionButton.vue";
const reduced = useReducedMotion();
const modal = ref(false), drawer = ref(false);
const toast = useToast();
const busy = ref(false);
const completed = ref(false);
const search = ref('');
const filterStatus = ref('');
const sampleVisible = computed(() => (!search.value.trim() || '统一网关 opsagent-gateway'.includes(search.value.trim())) && (!filterStatus.value || filterStatus.value === 'ACTIVE'));
let demoTimer: ReturnType<typeof setTimeout> | undefined;
function showActionDemo() {
  if (busy.value) return;
  completed.value = false;
  busy.value = true;
  demoTimer = setTimeout(() => { busy.value = false; completed.value = true; toast.show('组件反馈演示完成，未执行业务操作'); }, 900);
}
onBeforeUnmount(() => clearTimeout(demoTimer));
const colors = [
  { label: '浅蓝画布', token: '--oa-bg-canvas' }, { label: '白色表面', token: '--oa-bg-surface' },
  { label: '主色', token: '--oa-primary' }, { label: '标题文字', token: '--oa-text-primary' },
  { label: '辅助文字', token: '--oa-text-secondary' }, { label: '细边框', token: '--oa-border-default' },
];
const metadata = { alertname: 'OpsAgentServiceDown', instance: 'host.docker.internal:18080', job: 'opsagent-gateway', service: 'opsagent-gateway', severity: 'critical', environment: 'production' };
</script>

<template>
  <div class="stack-page foundation-preview">
    <PageHeader :icon="Layers3" title="UI 基础样本" description="检查颜色、字体、控件、容器与交互状态在同一视觉基线中的表现"><template #meta><span>开发环境专用 · 本页均为组件示例</span></template></PageHeader>
    <section class="foundation-grid">
      <article class="panel foundation-section full"><header class="panel-header"><div><h3><Palette :size="17" />颜色与表面</h3><p>直接读取主题变量，随全局视觉合同更新</p></div></header><div class="foundation-color-grid"><div v-for="color in colors" :key="color.token" class="foundation-color"><span :style="{ background: `var(${color.token})` }" /><strong>{{ color.label }}</strong><code>{{ color.token }}</code></div></div></article>
      <article class="panel foundation-section"><header class="panel-header"><div><h3>字体层级</h3><p>统一字体、字重与行高</p></div></header><div class="foundation-stack"><div><span class="foundation-token-label">页面标题 · 26 / 34 / 600</span><h2 class="type-page">让每一步都有依据</h2></div><div><span class="foundation-token-label">区块标题 · 16 / 24 / 600</span><h3 class="type-section">清晰的服务协作</h3></div><p>正文 14 / 22 / 400。中文、English 与 0123456789 使用一致的阅读基线。</p><small>辅助信息与时间 · 12 / 18 / 400</small><code>opsagent-gateway:18080</code></div></article>
      <article class="panel foundation-section"><header class="panel-header"><div><h3>控件与状态</h3><p>38px 常规控件 · 32px 紧凑控件</p></div></header><div class="foundation-stack"><div class="foundation-row"><button class="button primary" @click="toast.show('主要操作的反馈示例')">主要操作</button><button class="button secondary" @click="modal = true">次要操作</button><button class="button secondary" disabled>禁用操作</button><button class="icon-button" title="状态示例" aria-label="显示状态示例" @click="toast.show('图标按钮反馈示例', 'info')"><CheckCircle2 :size="16" /></button></div><div class="foundation-row"><StatusBadge value="PROCESSING" /><StatusBadge value="ACTIVE" /><StatusBadge value="WARNING" /><PriorityIndicator value="URGENT" /></div><FormField label="服务名称"><input value="opsagent-gateway" /></FormField><FormField label="运行环境"><select><option>生产环境</option><option>测试环境</option></select></FormField><FormField label="处置说明" help="表单样本不会保存到业务系统"><textarea rows="2">记录诊断依据和执行结果</textarea></FormField></div></article>
      <article class="panel foundation-section full"><header class="panel-header"><div><h3>交互反馈</h3><p>悬停、焦点、弹层与操作状态；业务内页不自动轮播</p></div><span class="foundation-motion-state">{{ reduced ? '减少动态效果：开启' : '标准动态效果' }}</span></header><div class="foundation-stack"><div class="foundation-row"><ActionButton class="primary" :loading="busy" :success="completed" loading-text="演示处理中…" success-text="再次演示" @click="showActionDemo">演示按钮反馈</ActionButton><button class="button secondary" @click="modal = true">打开弹窗</button><button class="button secondary" @click="drawer = true">打开抽屉</button><button class="button secondary" @click="toast.show('这是用于组件验收的错误提示', 'error')">错误提示</button></div><p class="foundation-help">系统开启减少动态效果后，关闭装饰动画与位移动效，内容和操作始终可用。</p></div></article>
      <article class="panel foundation-section full"><header class="panel-header"><div><h3>数据与技术信息</h3><p>以示例数据检查标签、边框和信息层级</p></div></header><DescriptionList><div><dt>服务</dt><dd><Server :size="16" /> 统一网关（示例）</dd></div><div><dt>采集状态</dt><dd><StatusBadge value="ACTIVE" /></dd></div></DescriptionList><TechnicalMetadata :metadata="metadata" /></article>
      <ListSurface class="full"><template #toolbar><div class="oa-filter-bar"><input v-model="search" aria-label="筛选示例记录" placeholder="搜索示例记录" /><select v-model="filterStatus" aria-label="筛选示例状态"><option value="">全部状态</option><option value="ACTIVE">正常</option><option value="WARNING">需关注</option></select><button class="button secondary" @click="search = ''; filterStatus = ''">重置</button></div></template><table v-if="sampleVisible"><thead><tr><th>服务（示例）</th><th>状态</th><th>更新时间</th></tr></thead><tbody><tr><td><strong>统一网关</strong><small class="block-muted">opsagent-gateway</small></td><td><StatusBadge value="ACTIVE" /></td><td>2026-09-05 09:30</td></tr></tbody></table><EmptyState v-else title="暂无匹配示例" description="重置筛选后查看表格样本。" :icon="AlertTriangle" /></ListSurface>
      <article class="panel foundation-section full"><EmptyState title="暂无匹配数据" description="空状态示例：说明当前情况，并提供清晰的下一步。" :icon="AlertTriangle" /></article>
    </section>
    <BaseModal v-if="modal" title="弹窗与焦点示例" @close="modal = false"><p>按 Tab 在弹窗内移动焦点，按 Esc 关闭后返回原操作按钮。</p><button class="button primary" @click="modal = false">完成查看</button></BaseModal>
    <DetailPanel v-if="drawer" title="详情抽屉示例" subtitle="独立的补充信息区域" @close="drawer = false"><p>按 Esc 关闭。开启减少动态效果时，抽屉内容直接呈现。</p></DetailPanel>
  </div>
</template>

<style scoped>
.foundation-section .panel-header h3 { display: flex; align-items: center; gap: 8px; }
.foundation-color-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 16px; padding: 24px; }
.foundation-color { min-width: 0; display: grid; gap: 9px; }
.foundation-color > span { height: 66px; border: 1px solid var(--oa-border-default); border-radius: 12px; }
.foundation-color strong { color: var(--oa-text-primary); font-size: var(--oa-font-size-sm); font-weight: 600; }
.foundation-color code { color: var(--oa-text-tertiary); font-size: 10px; overflow-wrap: anywhere; }
.foundation-token-label { display: block; margin-bottom: 7px; color: var(--oa-text-tertiary); font-size: var(--oa-font-size-xs); }
.foundation-stack h2, .foundation-stack h3, .foundation-stack p { margin: 0; }
.foundation-stack p { line-height: var(--oa-line-height-body); }
.foundation-stack > small, .foundation-help, .foundation-motion-state { color: var(--oa-text-secondary); font-size: var(--oa-font-size-xs); }
.foundation-section > .foundation-stack { padding: 24px; gap: 18px; }
.foundation-motion-state { padding: 6px 10px; border-radius: 8px; background: var(--oa-bg-subtle); }
@media (max-width: 1000px) { .foundation-color-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 640px) { .foundation-color-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 20px; }.foundation-section > .foundation-stack { padding: 20px; }.foundation-section .panel-header { flex-wrap: wrap; gap: 12px; } }
</style>