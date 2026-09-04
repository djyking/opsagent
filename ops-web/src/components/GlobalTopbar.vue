<script setup lang="ts">
import { Bell, Bot, ChevronDown, FileUp, Menu, Plus, Search, TicketCheck } from "@lucide/vue";
import { computed, ref } from "vue";
import { useRouter } from "vue-router";

const props = defineProps<{
  domainTitle: string;
  pageTitle: string;
  isAdmin: boolean;
}>();

const emit = defineEmits<{ menu: [] }>();
const router = useRouter();
const keyword = ref("");

const searchLabel = computed(() => {
  if (props.domainTitle === "AI 智能") return "搜索工单，或从创建菜单开始 AI 工作";
  return "搜索工单、编号或描述…";
});

function searchTicket() {
  const value = keyword.value.trim();
  if (!value) return;
  router.push({ path: "/tickets", query: { keyword: value } });
}
</script>

<template>
  <header class="global-topbar">
    <button class="icon-button topbar-menu" title="打开导航" @click="emit('menu')">
      <Menu :size="18" />
    </button>
    <div class="topbar-breadcrumb">
      <span>{{ domainTitle }}</span><i>/</i><strong>{{ pageTitle }}</strong>
    </div>
    <form class="topbar-search" role="search" @submit.prevent="searchTicket">
      <Search :size="15" />
      <input v-model="keyword" aria-label="搜索工单" :placeholder="searchLabel" />
      <kbd>Enter</kbd>
    </form>
    <details class="topbar-create-menu">
      <summary class="button primary topbar-create">
        <Plus :size="15" />创建<ChevronDown :size="13" />
      </summary>
      <div class="topbar-create-popover">
        <RouterLink to="/tickets?create=1"><TicketCheck :size="16" /><span><strong>新建工单</strong><small>记录并推进运维问题</small></span></RouterLink>
        <RouterLink to="/rag/chat?new=1"><Bot :size="16" /><span><strong>新会话</strong><small>从知识检索开始分析</small></span></RouterLink>
        <RouterLink to="/knowledge"><FileUp :size="16" /><span><strong>上传知识文档</strong><small>进入知识库并选择目标库</small></span></RouterLink>
      </div>
    </details>
    <RouterLink v-if="isAdmin" class="icon-button" to="/notifications" title="通知中心">
      <Bell :size="17" />
    </RouterLink>
    <span class="topbar-health" title="系统监控显示全部服务的实时状态"><i />服务正常</span>
  </header>
</template>
