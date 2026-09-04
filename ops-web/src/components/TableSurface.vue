<template>
  <section class="oa-table-surface">
    <header v-if="$slots.header" class="oa-table-surface-header"><slot name="header" /></header>
    <div class="responsive-table"><slot /></div>
    <footer v-if="$slots.footer" class="oa-table-surface-footer"><slot name="footer" /></footer>
  </section>
</template>
