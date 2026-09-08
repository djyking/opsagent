<script setup lang="ts">
import type { RagStreamResult } from '@/api/rag-stream';
defineProps<{ result: RagStreamResult }>();
</script>
<template><details v-if="result.metadata?.budgetLimit != null" class="rag-budget-evidence"><summary>本次调用额度与记录</summary><p>本次累计额度 {{ result.metadata.budgetLimit.toLocaleString('zh-CN') }} token · {{ result.metadata.requestAttempts ?? '未取得' }} 次请求</p><p v-if="result.metadata.budgetChargedTokens != null">{{ result.metadata.budgetUsageKnown === true ? '已核对的累计用量' : '含未知用量的保守预留' }}：{{ result.metadata.budgetChargedTokens.toLocaleString('zh-CN') }} token</p><p v-if="result.metadata.budgetUsageKnown !== true">未知部分按预算预留计算，不代表精确计费用量。</p><p>额度按本次问答的全部输入、输出和重试累计。</p></details></template>
<style scoped>.rag-budget-evidence { margin-top:12px; color:#7b8fa9; font-size:11px; }.rag-budget-evidence summary { cursor:pointer; }.rag-budget-evidence p { margin:7px 0 0; line-height:1.7; }</style>
