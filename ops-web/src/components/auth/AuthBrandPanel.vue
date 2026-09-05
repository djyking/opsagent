<script setup lang="ts">
import { Activity, ArrowRight, BookOpen, Check, ShieldCheck, TicketCheck, UsersRound } from "@lucide/vue";
</script>

<template>
  <section class="register-brand-panel" aria-label="OpsAgent 协作工作台介绍">
    <div class="register-brand"><span><Activity :size="25" :stroke-width="1.8" /></span><strong>OpsAgent</strong><small>智能运维工作台</small></div>
    <div class="register-brand-content">
      <span class="register-brand-kicker"><UsersRound :size="15" />让协作从这里开始</span>
      <h1><span class="register-title-wide">一起把复杂的现场，<br /><em>变成清晰的下一步。</em></span><span class="register-title-compact">让协作从这里开始。</span></h1>
      <p>连接问题、知识与处理记录，<br />让每一次协作都有迹可循。</p>
      <div class="register-workflow" aria-hidden="true">
        <div class="register-workflow-top"><span><TicketCheck :size="22" /></span><div><strong>从一个问题开始</strong><small>创建工单，说明需要解决的事项</small></div></div>
        <div class="register-workflow-path"><i /><ArrowRight :size="16" /><i /></div>
        <div class="register-workflow-bottom"><div><BookOpen :size="19" /><strong>找到知识依据</strong><small>查看知识与引用</small></div><div><Check :size="19" /><strong>关注处理进展</strong><small>协作与记录相连</small></div></div>
      </div>
    </div>
    <p class="register-brand-note"><ShieldCheck :size="16" />新账号默认获得普通用户权限，更多权限由管理员分配。</p>
  </section>
</template>

<style scoped>
.register-brand-panel { display: flex; flex-direction: column; min-width: 0; padding: 36px clamp(32px, 5vw, 96px) 28px; background: radial-gradient(ellipse at 0 0, #deecff, transparent 55%), radial-gradient(ellipse at 95% 80%, #dcf3f2, transparent 42%), #f0f6fe; color: #234366; }
.register-brand { display: flex; align-items: center; gap: 12px; }
.register-brand > span { display: grid; place-items: center; width: 44px; height: 44px; background: white; border: 1px solid #d9e5f7; border-radius: 13px; color: var(--oa-primary); box-shadow: 0 5px 16px #527ba312; }
.register-brand strong { font-size: 24px; font-weight: 650; letter-spacing: -.5px; }
.register-brand small { margin-left: 4px; padding-left: 16px; border-left: 1px solid #c3d5e9; color: #667e97; font-size: 12px; }
.register-brand-content { max-width: 620px; width: 100%; margin: auto; padding: 48px 0; }
.register-brand-kicker { display: inline-flex; gap: 8px; align-items: center; color: #537798; font-size: 12px; }
.register-brand-kicker svg { color: var(--oa-primary); }
.register-brand-content h1 { margin: 20px 0; font-size: clamp(30px, 2.7vw, 48px); line-height: 1.4; font-weight: 650; letter-spacing: -.025em; }
.register-title-compact { display: none; }
.register-brand-content em { color: var(--oa-primary); font-style: normal; }
.register-brand-content > p { margin: 0; color: #667e97; font-size: 14px; line-height: 1.85; }
.register-workflow { max-width: 470px; padding: 25px; margin: 38px 0 0; background: #ffffffdb; border: 1px solid #d9e5f3; border-radius: 20px; box-shadow: 0 12px 28px #527ba310; }
.register-workflow-top { display: flex; align-items: center; gap: 14px; }
.register-workflow-top > span { display: grid; place-items: center; width: 44px; height: 44px; color: var(--oa-primary); background: #edf2ff; border-radius: 13px; }
.register-workflow strong, .register-workflow small { display: block; }
.register-workflow strong { font-size: 14px; font-weight: 600; }
.register-workflow small { margin-top: 5px; font-size: 12px; color: #667e97; }
.register-workflow-path { display: flex; align-items: center; gap: 12px; margin: 23px 0; color: #89a5ce; }
.register-workflow-path i { height: 1px; flex: 1; background: #dce7f3; }
.register-workflow-bottom { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.register-workflow-bottom > div { padding: 16px; border: 1px solid #e4edf7; border-radius: 12px; background: #f6f9fe; }
.register-workflow-bottom svg { color: #4b6fe7; margin-bottom: 10px; }
.register-workflow-bottom > div:last-child svg { color: #388c77; }
.register-brand-note { display: flex; align-items: flex-start; gap: 8px; margin: 0; color: #71879d; font-size: 11px; line-height: 1.7; }
.register-brand-note svg { flex: none; margin-top: 2px; }
@media (max-width: 1150px) { .register-brand-panel { padding: 30px; }.register-brand small { display: none; }.register-workflow { padding: 20px; }.register-workflow-bottom > div { padding: 12px; } }
@media (max-width: 900px) { .register-brand-panel { padding: 22px 28px; border-bottom: 1px solid #dfe9f5; }.register-brand > span { width: 34px; height: 34px; border-radius: 10px; }.register-brand strong { font-size: 20px; }.register-brand small { display: block; border: 0; margin-left: auto; padding: 0; }.register-brand-content { margin: 0; padding: 18px 0 0; }.register-brand-content h1 { margin: 0; font-size: 24px; }.register-title-compact { display: block; }.register-title-wide, .register-brand-kicker, .register-brand-content > p, .register-workflow, .register-brand-note { display: none; } }
@media (max-width: 380px) { .register-brand small { display: none; }.register-brand-content h1 { font-size: 22px; } }
</style>
