<script setup lang="ts">
import { AlertTriangle, CheckCircle2, Server } from "@lucide/vue";
import PageHeader from "@/components/PageHeader.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import PriorityIndicator from "@/components/PriorityIndicator.vue";
import ListSurface from "@/components/ListSurface.vue";
import FormField from "@/components/FormField.vue";
import DescriptionList from "@/components/DescriptionList.vue";
import TechnicalMetadata from "@/components/TechnicalMetadata.vue";
import EmptyState from "@/components/EmptyState.vue";

const metadata = { alertname: "OpsAgentServiceDown", instance: "host.docker.internal:18080", job: "opsagent-gateway", service: "opsagent-gateway", severity: "critical", environment: "production" };
</script>

<template>
  <div class="stack-page foundation-preview">
    <PageHeader title="UI Foundation" description="字体、颜色、尺寸、Surface 与交互状态基线" />
    <section class="foundation-grid">
      <article class="panel foundation-section"><header class="panel-header"><h3>Typography</h3></header><div class="foundation-stack"><h2 class="type-page">页面标题 22 / 30 / 600</h2><h3 class="type-section">Section 标题 16 / 24 / 600</h3><p>正文 14 / 22 / 400，中文 English 与 0123456789 使用同一基线。</p><small>辅助信息与时间 12 / 18 / 400</small><code>opsagent-gateway:18080</code></div></article>
      <article class="panel foundation-section"><header class="panel-header"><h3>Controls</h3></header><div class="foundation-stack"><div class="foundation-row"><button class="button primary">主要操作</button><button class="button secondary">次要操作</button><button class="icon-button" title="健康"><CheckCircle2 :size="16" /></button><StatusBadge value="PROCESSING" /><PriorityIndicator value="URGENT" /></div><FormField label="服务名称"><input value="opsagent-gateway" /></FormField><FormField label="运行环境"><select><option>生产环境</option></select></FormField><FormField label="处置说明"><textarea rows="3">记录诊断依据和执行结果</textarea></FormField></div></article>
      <article class="panel foundation-section full"><header class="panel-header"><h3>Surface 与数据表达</h3></header><DescriptionList><div><dt>服务</dt><dd><Server :size="16" /> opsagent-gateway</dd></div><div><dt>健康状态</dt><dd><StatusBadge value="ACTIVE" /></dd></div></DescriptionList><TechnicalMetadata :metadata="metadata" /></article>
      <ListSurface class="full"><template #toolbar><div class="oa-filter-bar"><input placeholder="搜索记录" /><select><option>全部状态</option></select><button class="button secondary">重置</button></div></template><table><thead><tr><th>服务</th><th>状态</th><th>更新时间</th></tr></thead><tbody><tr><td><strong>统一网关</strong><small class="block-muted">opsagent-gateway</small></td><td><StatusBadge value="ACTIVE" /></td><td>2026-09-04 22:30</td></tr></tbody></table></ListSurface>
      <article class="panel foundation-section full"><EmptyState title="暂无匹配数据" description="调整筛选条件后重试" :icon="AlertTriangle" /></article>
    </section>
  </div>
</template>