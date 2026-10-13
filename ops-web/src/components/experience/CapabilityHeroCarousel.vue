<script setup lang="ts">
import { computed } from "vue";
import { useAuthStore } from "@/stores/auth";
import { capabilityStories } from "@/data/experience";
import StoryCarousel from "./StoryCarousel.vue";
const auth = useAuthStore();
const stories = computed(() => capabilityStories.filter(story => !story.admin || auth.isAdmin));
</script>
<template><StoryCarousel :stories="stories" label="AI 能力介绍" :interval="6000" variant="hero" /></template>
