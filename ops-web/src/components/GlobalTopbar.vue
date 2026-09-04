<script setup lang="ts">
import { Bell, Menu, Plus, Search } from "@lucide/vue";
import { ref } from "vue";
import { useRouter } from "vue-router";

defineProps<{
  domainTitle: string;
  pageTitle: string;
  isAdmin: boolean;
}>();

const emit = defineEmits<{ menu: [] }>();
const router = useRouter();
const keyword = ref("");

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
      <input v-model="keyword" aria-label="搜索工单" placeholder="搜索工单…" />
      <kbd>Enter</kbd>
    </form>
    <RouterLink class="button primary topbar-create" to="/tickets?create=1">
      <Plus :size="15" />新建工单
    </RouterLink>
    <RouterLink v-if="isAdmin" class="icon-button" to="/notifications" title="通知中心">
      <Bell :size="17" />
    </RouterLink>
    <span class="topbar-health"><i />服务运行中</span>
  </header>
</template>
