<template>
  <section class="oa-filter-bar" aria-label="筛选条件">
    <slot />
    <div v-if="$slots.actions" class="oa-filter-actions"><slot name="actions" /></div>
  </section>
</template>
