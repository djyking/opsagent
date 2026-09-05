<script setup lang="ts">
import { computed, ref } from "vue";
import { ArrowRight, BookCheck, MessageSquareText, Upload } from "@lucide/vue";
import { useAuthStore } from "@/stores/auth";
import { capabilities } from "@/data/experience";
import PageHeader from "@/components/PageHeader.vue";
import CapabilityOverview from "@/components/experience/CapabilityOverview.vue";
import CapabilityCard from "@/components/experience/CapabilityCard.vue";
const auth = useAuthStore();
const filter = ref('all');
const available = computed(() => capabilities.filter(c => !c.admin || auth.isAdmin));
const filtered = computed(() => available.value.filter(c => filter.value === 'all' || (filter.value === 'knowledge' ? ['knowledge','review'].includes(c.key) : c.key === filter.value)));
const filters = computed(() => [{ key:'all', label:'全部' }, { key:'rag', label:'问答' }, { key:'knowledge', label:'知识' }, ...(auth.isAdmin ? [{key:'index',label:'索引'}] : [])]);
</script>
<template>
  <div class="stack-page ai-center">
    <PageHeader title="AI 能力中心" description="从一个明确任务开始，让知识成为处置的依据" />
    <CapabilityOverview />
    <section class="quick-task-bar" :class="{ 'quick-task-bar--standard': !auth.isAdmin }" aria-label="快捷任务">
      <div><strong>接下来，想做什么？</strong><small>选择任务，进入工作页面</small></div>
      <RouterLink to="/rag/chat"><MessageSquareText :size="18" /><span>询问运维问题</span><ArrowRight :size="16" /></RouterLink>
      <RouterLink to="/knowledge?upload=1"><Upload :size="18" /><span>上传知识文档</span><ArrowRight :size="16" /></RouterLink>
      <RouterLink v-if="auth.isAdmin" to="/knowledge/review"><BookCheck :size="18" /><span>处理待审核知识</span><ArrowRight :size="16" /></RouterLink>
    </section>
    <section aria-label="当前可用能力">
      <header class="capabilities-heading">
        <div><h2>当前可用能力</h2><p>按权限展示你可以使用的入口</p></div>
        <div class="capability-filters" aria-label="筛选能力"><button v-for="item in filters" :key="item.key" :aria-pressed="filter === item.key" @click="filter = item.key">{{ item.label }}</button></div>
      </header>
      <div class="capability-grid" :class="{ 'capability-grid--standard': !auth.isAdmin }"><CapabilityCard v-for="capability in filtered" :key="capability.key" :capability="capability" /></div>
    </section>
  </div>
</template>
