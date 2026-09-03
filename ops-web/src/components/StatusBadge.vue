<script setup lang="ts">
defineProps<{ value: string }>();
const labels: Record<string, string> = {
  CREATED: "待接单",
  ASSIGNED: "已接单",
  PROCESSING: "处理中",
  SUSPENDED: "已挂起",
  WAITING_CONFIRM: "待业务确认",
  RESOLVED: "待确认",
  CLOSED: "已关闭",
  REJECTED: "已驳回",
  UNREAD: "未读",
  READ: "已读",
  PENDING: "待处理",
  PARSING: "解析中",
  SUCCESS: "成功",
  FAILED: "失败",
  SENT: "已发送",
  LOW: "低",
  MEDIUM: "中",
  HIGH: "高",
  URGENT: "紧急",
};
</script>
<template>
  <span class="status-badge" :class="`status-${value.toLowerCase()}`"
    ><i />{{ labels[value] || value }}</span
  >
</template>
