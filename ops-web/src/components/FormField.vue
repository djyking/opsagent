<script setup lang="ts">
defineProps<{ label: string; help?: string; error?: string }>();
</script>

<template>
  <label class="oa-form-field">
    <span class="oa-form-field-label">{{ label }}</span>
    <slot />
    <small v-if="help" class="oa-form-field-help">{{ help }}</small>
    <small v-if="error" class="oa-form-field-error">{{ error }}</small>
  </label>
</template>