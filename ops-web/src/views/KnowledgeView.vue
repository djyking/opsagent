<script setup lang="ts">
import { onMounted, ref } from "vue";
import { request } from "@/api/http";
const bases = ref<Record<string, unknown>[]>([]);
const name = ref("");
const error = ref("");
async function load() {
  try {
    bases.value = await request<Record<string, unknown>[]>({
      url: "/api/knowledge/bases",
    });
  } catch (e) {
    error.value = e instanceof Error ? e.message : "加载失败";
  }
}
async function create() {
  if (!name.value.trim()) return;
  await request<number>({
    method: "POST",
    url: "/api/knowledge/bases",
    data: { name: name.value },
  });
  name.value = "";
  await load();
}
onMounted(load);
</script>
<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <span class="eyebrow">KNOWLEDGE</span>
        <h3>知识库</h3>
      </div>
    </header>
    <div class="upload-strip">
      <input v-model="name" placeholder="新知识库名称" /><button
        class="button primary"
        @click="create"
      >
        创建
      </button>
    </div>
    <p v-if="error" class="inline-error">{{ error }}</p>
    <div v-if="!bases.length" class="empty-state">暂无知识库</div>
    <div v-else class="document-list">
      <article v-for="base in bases" :key="String(base.id)">
        <div class="file-info">
          <strong>{{ base.name }}</strong
          ><span>{{ base.description || "暂无描述" }}</span>
        </div>
      </article>
    </div>
  </section>
</template>
