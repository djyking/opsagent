<script setup lang="ts">
import { Activity } from "@lucide/vue";
import StoryCarousel from "@/components/experience/StoryCarousel.vue";
import { authStories } from "@/data/experience";
</script>
<template><section class="auth-experience"><div class="auth-brand"><span><Activity :size="22" /></span>OpsAgent</div><StoryCarousel :stories="authStories" label="OpsAgent 产品故事" :interval="5500" variant="auth" /><p class="auth-story-footer">连接运维现场，让处理过程清晰可见。</p></section></template>
