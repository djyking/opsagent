<template>
  <section class="oa-list-surface">
    <header v-if="$slots.header" class="oa-list-surface-header"><slot name="header" /></header>
    <div v-if="$slots.toolbar" class="oa-list-surface-toolbar"><slot name="toolbar" /></div>
    <div class="oa-list-surface-body"><slot /></div>
    <footer v-if="$slots.footer" class="oa-list-surface-footer"><slot name="footer" /></footer>
  </section>
</template>