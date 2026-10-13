<script setup lang="ts">
import { renderAnswer } from '@/utils/answer-markdown';
const props = defineProps<{ content?: string }>();
const RenderedAnswer = () => renderAnswer(props.content || '');
</script>
<template><RenderedAnswer /></template>
