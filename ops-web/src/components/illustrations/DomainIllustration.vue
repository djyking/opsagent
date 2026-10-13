<script setup lang="ts">
import { Activity, ArrowDown, ArrowRight, BookOpen, Check, Database, FileText, GitBranch, Search, ShieldCheck, TicketCheck } from "@lucide/vue";
import type { IllustrationKind } from "@/data/experience";
defineProps<{ kind: IllustrationKind }>();
</script>
<template>
  <div class="domain-illustration" :class="`illustration-${kind}`" aria-hidden="true">
    <div class="illustration-caption"><i /><i /><i /><span>流程示意 · 非实时数据</span></div>
    <template v-if="kind === 'rag'">
      <div class="mini-question"><Search :size="18" /><span>告警出现后，应该先检查什么？</span></div>
      <div class="evidence-pair"><div><BookOpen :size="18" /><strong>运维手册</strong><small>核对处置步骤</small></div><div><FileText :size="18" /><strong>知识片段</strong><small>保留原文依据</small></div></div>
      <ArrowDown class="illustration-connector" :size="22" />
      <div class="mini-answer"><ShieldCheck :size="20" /><div><strong>建议与来源，一起呈现</strong><p>先核对告警，再定位影响范围。</p><span class="mini-citation">[来源] 查看原文</span></div></div>
    </template>
    <template v-else-if="kind === 'alert'">
      <div class="mini-event"><span class="illustration-icon"><Activity :size="23" /></span><div><small>告警事件</small><strong>服务异常需要跟进</strong></div><span class="mini-tag">关联</span></div>
      <div class="mini-chain"><div><TicketCheck :size="23" /><strong>创建工单</strong><small>明确责任人</small></div><ArrowRight :size="19" /><div><GitBranch :size="23" /><strong>关联服务</strong><small>定位影响</small></div></div>
      <div class="mini-answer"><Check :size="20" /><div><strong>处置过程可追踪</strong><p>诊断 · 执行 · 验证 · 归档</p></div></div>
    </template>
    <template v-else-if="kind === 'knowledge'">
      <div class="mini-event"><FileText :size="24" /><div><small>团队知识</small><strong>把经验留在文档里</strong></div></div>
      <ol class="mini-pipeline"><li v-for="(step, index) in ['上传文档', '解析切片', '审核发布', '检索引用']" :key="step"><span>{{ index + 1 }}</span><strong>{{ step }}</strong><Check :size="15" /></li></ol>
    </template>
    <template v-else-if="kind === 'index'">
      <div class="mini-event"><Database :size="24" /><div><small>索引检查</small><strong>从状态到修复路径</strong></div></div>
      <div class="mini-index-row" v-for="row in ['Elasticsearch · 文本索引', 'Qdrant · 向量集合', '一致性 · 缺失与孤儿项']" :key="row"><span>{{ row }}</span><Search :size="16" /></div>
      <div class="mini-footnote">检查结果以索引管理页面为准</div>
    </template>
    <template v-else>
      <div class="mini-event"><ShieldCheck :size="24" /><div><small>处置与审计</small><strong>每一步都有依据</strong></div></div>
      <ol class="mini-pipeline"><li v-for="(step, index) in ['记录诊断依据', '执行处置动作', '验证恢复结果', '保留操作审计']" :key="step"><span>{{ index + 1 }}</span><strong>{{ step }}</strong><Check :size="15" /></li></ol>
    </template>
  </div>
</template>
