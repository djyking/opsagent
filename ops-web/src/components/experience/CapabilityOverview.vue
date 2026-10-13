<script setup lang="ts">
import { ArrowRight, BookOpen, Bot, FileCheck2, Sparkles } from "@lucide/vue";
</script>

<template>
  <section class="capability-overview" aria-label="AI 与知识工作区介绍">
    <div class="capability-overview-copy">
      <span class="experience-kicker"><Sparkles :size="15" /> 与知识一起，找到下一步</span>
      <h2>让每一个答案，<br />都有可以核对的依据。</h2>
      <p>整理团队经验，检索可信来源，把知识带入日常运维。</p>
      <RouterLink class="button primary" to="/rag/chat">开始智能问答 <ArrowRight :size="16" /></RouterLink>
    </div>
    <div class="capability-overview-map" aria-label="知识问答流程示意">
      <div class="capability-map-source"><span><BookOpen :size="21" /></span><div><strong>团队知识</strong><small>故障手册 · 操作规范</small></div></div>
      <div class="capability-map-path" aria-hidden="true"><i /><ArrowRight :size="17" /><i /></div>
      <div class="capability-map-answer"><span><Bot :size="24" /></span><div><strong>基于来源的回答</strong><small>检索知识，辅助分析</small></div><FileCheck2 class="capability-map-check" :size="19" /></div>
      <p><FileCheck2 :size="14" /> 查看引用，再核对建议 <small>流程示意</small></p>
    </div>
  </section>
</template>
