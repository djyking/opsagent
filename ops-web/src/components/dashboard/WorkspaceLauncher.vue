<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { Activity, ArrowRight, BookCheck, BookOpen, CalendarClock, CheckCheck, DatabaseZap, Layers, MessageSquareText, Network, Search, ShieldCheck, Siren, Sparkles, Ticket, TicketCheck, TimerReset, X } from "@lucide/vue";
import { useAuthStore } from "@/stores/auth";
import { workspaceActions, searchWorkspaceActions, actionDestination, type WorkspaceAction, type WorkspaceActionIcon } from "@/data/workspace-actions";

const auth = useAuthStore();
const router = useRouter();
const root = ref<HTMLElement>();
const input = ref<HTMLInputElement>();
const query = ref("");
const open = ref(false);
const selected = ref(0);
const now = ref(new Date());
let dateTimer: ReturnType<typeof setInterval> | undefined;
const dateLabel = computed(() => now.value.toLocaleDateString("zh-CN", { month: "long", day: "numeric", weekday: "long" }));
const greeting = computed(() => {
  const hour = now.value.getHours();
  return hour < 6 ? "夜深了" : hour < 12 ? "上午好" : hour < 18 ? "下午好" : "晚上好";
});
const name = computed(() => auth.user?.displayName || auth.user?.username || "运维伙伴");
const matches = computed(() => searchWorkspaceActions(query.value, auth.isAdmin).slice(0, 6));
const fallbacks = computed(() => workspaceActions.filter(action => ["ticket-search", "rag"].includes(action.id)));
const options = computed(() => matches.value.length ? matches.value : fallbacks.value);
const selectedMatch = computed(() => query.value.trim() && matches.value.length ? matches.value[selected.value] || matches.value[0] : undefined);
const actionLabel = computed(() => selectedMatch.value ? '打开入口' : query.value.trim() ? '查看候选' : '浏览入口');
const quickGoals = ["创建工单", "排查 Redis", "查看值班", "服务拓扑"];
const icons: Record<WorkspaceActionIcon, typeof Search> = {
  TicketCheck, MessageSquareText, BookOpen, CalendarClock, TimerReset, Network,
  Activity, Siren, BookCheck, DatabaseZap, ShieldCheck,
};
watch(query, () => { selected.value = 0; open.value = true; });
watch(options, () => { selected.value = Math.min(selected.value, Math.max(0, options.value.length - 1)); });

function close() { open.value = false; }
function leave(event: FocusEvent) {
  if (!root.value?.contains(event.relatedTarget as Node | null)) close();
}
function outside(event: PointerEvent) {
  if (!root.value?.contains(event.target as Node)) close();
}
async function choose(action: WorkspaceAction) {
  close();
  await router.push(actionDestination(action, query.value));
}
function submit() {
  if (selectedMatch.value) void choose(selectedMatch.value);
  else void matchGoal();
}
function onInputKeydown(event: KeyboardEvent) {
  // Candidate navigation belongs to the input method while Chinese text is composing.
  if (event.isComposing || event.keyCode === 229) return;
  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    event.preventDefault();
    void move(event.key === "ArrowDown" ? 1 : -1);
  } else if (event.key === "Enter") {
    event.preventDefault();
    submit();
  } else if (event.key === "Escape") {
    event.preventDefault();
    close();
  }
}
async function move(direction: number) {
  if (!open.value) { open.value = true; selected.value = direction > 0 ? 0 : options.value.length - 1; }
  else selected.value = (selected.value + direction + options.value.length) % options.value.length;
  await nextTick();
  root.value?.querySelector(`#workspace-option-${selected.value}`)?.scrollIntoView({ block: "nearest" });
}
async function matchGoal(goal?: string) {
  if (goal) query.value = goal;
  selected.value = 0;
  open.value = true;
  await nextTick();
  input.value?.focus();
}
onMounted(() => {
  document.addEventListener("pointerdown", outside);
  dateTimer = setInterval(() => { now.value = new Date(); }, 60_000);
});
onBeforeUnmount(() => {
  document.removeEventListener("pointerdown", outside);
  if (dateTimer) clearInterval(dateTimer);
});
</script>

<template>
  <section ref="root" class="workspace-launcher" aria-labelledby="workspace-welcome" @focusout="leave">
    <div class="workspace-launcher-copy">
      <div class="workspace-eyebrow"><span><Sparkles :size="14" /> 从这里，开始今天的工作</span><time :datetime="now.toISOString()">{{ dateLabel }}</time></div>
      <h2 id="workspace-welcome">{{ greeting }}，{{ name }}<span class="welcome-dot" aria-hidden="true">✦</span></h2>
      <p class="workspace-lead">今天想处理什么？找到入口，让下一步更清楚。</p>
      <div class="workspace-search-box">
        <form class="workspace-search-form" role="search" aria-label="操作与能力搜索" @submit.prevent>
          <span class="workspace-search-symbol"><Search :size="20" /></span>
          <input id="workspace-goal" ref="input" v-model="query" type="text" maxlength="500" autocomplete="off" placeholder="例如：排查 Redis、创建工单、查看值班" aria-label="搜索操作与能力" role="combobox" aria-autocomplete="list" aria-controls="workspace-results" :aria-expanded="open" :aria-activedescendant="open && options.length ? `workspace-option-${selected}` : undefined" @focus="open = true" @keydown="onInputKeydown" />
          <button v-if="query" type="button" class="workspace-clear" aria-label="清空能力搜索" @click="query = ''; input?.focus()"><X :size="15" /></button>
          <button type="button" class="button primary workspace-match" :title="selectedMatch ? `打开：${selectedMatch.label}` : '展开可用入口，选择后继续'" @click="submit">{{ actionLabel }} <ArrowRight :size="16" /></button>
        </form>
        <div class="workspace-quick-goals"><span>快捷开始</span><button v-for="goal in quickGoals" :key="goal" type="button" @click="matchGoal(goal)">{{ goal }}</button></div>
        <div v-if="open" class="workspace-results-panel">
          <div class="workspace-results-heading" aria-live="polite"><strong>{{ query.trim() ? (matches.length ? '可用的操作与能力' : '暂未匹配到对应能力') : '常用入口' }}</strong><small>{{ matches.length ? '选择入口继续' : '可以继续查工单或向知识库提问' }}</small></div>
          <div id="workspace-results" class="workspace-results" role="listbox" aria-label="匹配的功能入口">
            <button v-for="(action, index) in options" :id="`workspace-option-${index}`" :key="action.id" type="button" role="option" tabindex="-1" :aria-selected="selected === index" class="workspace-result" @pointermove="selected = index" @mousedown.prevent @click="choose(action)">
              <span class="workspace-result-icon"><component :is="icons[action.icon] || Search" :size="19" /></span>
              <span class="workspace-result-copy"><strong>{{ action.label }}</strong><small>{{ action.description }}</small></span>
              <ArrowRight :size="16" />
            </button>
          </div>
          <div class="workspace-results-footer"><span>{{ selectedMatch ? '↑ ↓ 选择 · Enter 或右侧按钮打开' : '请选择候选入口继续' }} · Esc 关闭</span><span>问答内容将带入草稿</span></div>
        </div>
      </div>
    </div>
    <div class="workspace-visual" aria-hidden="true">
      <div class="workspace-orbit orbit-outer" /><div class="workspace-orbit orbit-inner" />
      <div class="workspace-core"><Layers :size="36" :stroke-width="1.4" /><span>OpsAgent</span></div>
      <div class="workspace-node node-knowledge"><span><BookOpen :size="18" /></span><div><strong>知识有据</strong><small>检索 · 问答</small></div></div>
      <div class="workspace-node node-collaboration"><span><Ticket :size="18" /></span><div><strong>协作有序</strong><small>工单 · 值班</small></div></div>
      <div class="workspace-visual-caption"><CheckCheck :size="14" /> 连接知识、协作与运维现场</div>
    </div>
  </section>
</template>

<style scoped>
.workspace-launcher { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) 290px; align-items: center; gap: 24px; padding: 28px 32px; border: 1px solid #d8e5f6; border-radius: var(--oa-radius-raised); background: radial-gradient(ellipse at 90% 10%, #d6e7ff99, transparent 48%), linear-gradient(115deg, #f8fbff 10%, #edf5ff 68%, #edf8f6); box-shadow: var(--oa-shadow-card); }
.workspace-launcher-copy { min-width: 0; }
.workspace-eyebrow { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 20px; margin-bottom: 12px; color: #6d86a1; font-size: 12px; line-height: 20px; }
.workspace-eyebrow > span { display: inline-flex; align-items: center; gap: 6px; }
.workspace-eyebrow time { color: var(--oa-text-tertiary); }
.workspace-launcher h2 { display: flex; align-items: center; gap: 14px; margin: 0; font-size: 28px; font-weight: 600; line-height: 40px; letter-spacing: -.03em; overflow-wrap: anywhere; }
.welcome-dot { color: #6e94ef; font-size: 26px; }
.workspace-lead { margin: 7px 0 21px; color: var(--oa-text-secondary); font-size: 13px; line-height: 22px; }
.workspace-search-box { position: relative; padding: 9px 12px; border: 1px solid #dbe6f5; border-radius: 15px; background: #fff; box-shadow: 0 8px 22px #6484ac0a; }
.workspace-search-form { display: flex; align-items: center; gap: 9px; }
.workspace-search-symbol { width: 36px; height: 36px; flex: none; display: grid; place-items: center; border-radius: 10px; color: var(--oa-primary); background: var(--oa-primary-soft); }
.workspace-search-form input { min-width: 0; flex: 1; width: 100%; height: 42px; padding: 6px 2px; border: 0; border-radius: 4px; box-shadow: none; background: transparent; font-size: 13px; }
.workspace-search-form input:focus-visible { outline: 2px solid #a7c1f6; outline-offset: 1px; }
.workspace-search-form input::placeholder { color: #8a9bb0; }
.workspace-match { flex: none; height: 38px; gap: 8px; padding-inline: 14px; font-size: 12px; }
.workspace-clear { display: grid; place-items: center; flex: none; width: 26px; height: 30px; border: 0; background: transparent; color: var(--oa-text-tertiary); border-radius: 6px; }
.workspace-clear:hover { background: var(--oa-bg-hover); }
.workspace-quick-goals { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; margin-top: 8px; padding-top: 9px; border-top: 1px solid #edf2f8; }
.workspace-quick-goals > span { margin-right: 4px; color: var(--oa-text-secondary); font-size: 11px; }
.workspace-quick-goals button { padding: 5px 9px; border: 1px solid transparent; border-radius: 7px; background: #f3f6fb; color: #617892; font-size: 11px; line-height: 17px; }
.workspace-quick-goals button:hover { border-color: #d8e4f5; color: var(--oa-primary); background: #eef4ff; }
.workspace-results-panel { position: absolute; z-index: 12; top: calc(100% + 8px); left: 0; right: 0; padding: 8px; border: 1px solid #d5e2f2; border-radius: 15px; background: white; box-shadow: var(--oa-shadow-float); }
.workspace-results-heading { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 5px 12px; padding: 8px 10px 12px; font-size: 12px; }
.workspace-results-heading strong { font-weight: 500; color: var(--oa-text-secondary); }
.workspace-results-heading small { font-size: 11px; color: var(--oa-text-tertiary); }
.workspace-results { max-height: 330px; overflow-y: auto; overscroll-behavior: contain; }
.workspace-result { width: 100%; display: grid; grid-template-columns: 38px minmax(0,1fr) 16px; align-items: center; gap: 10px; padding: 10px; border: 1px solid transparent; border-radius: 10px; background: transparent; text-align: left; color: var(--oa-text-secondary); }
.workspace-result[aria-selected="true"] { border-color: #dbe7f8; background: #f0f6ff; }
.workspace-result-icon { display: grid; place-items: center; width: 36px; height: 36px; border: 1px solid #e3ebf5; border-radius: 10px; background: white; color: var(--oa-primary); }
.workspace-result-copy { min-width: 0; display: grid; gap: 4px; }
.workspace-result-copy strong { color: var(--oa-text-primary); font-size: 13px; font-weight: 500; }
.workspace-result-copy small { color: var(--oa-text-secondary); font-size: 11px; line-height: 18px; }
.workspace-results-footer { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 5px 10px; margin-top: 7px; padding: 10px 10px 3px; border-top: 1px solid var(--oa-border-subtle); color: var(--oa-text-tertiary); font-size: 10px; }
.workspace-visual { position: relative; min-height: 208px; width: 290px; justify-self: center; }
.workspace-orbit { position: absolute; border: 1px solid #bdd2ee88; border-radius: 50%; transform: rotate(-24deg); }
.orbit-outer { inset: 23px 6px 33px; }.orbit-inner { inset: 42px 30px 49px; }
.workspace-core { position: absolute; left: 99px; top: 47px; width: 100px; height: 105px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 9px; border: 5px solid #ffffffc2; border-radius: 29px; background: linear-gradient(145deg,#9bbaf7,#6c94e7); box-shadow: 0 15px 30px #7499c930; color: white; transform: rotate(-7deg); }
.workspace-core span { font-size: 11px; font-weight: 600; letter-spacing: .03em; }
.workspace-node { position: absolute; display: flex; align-items: center; gap: 9px; padding: 11px 12px; border: 1px solid #d8e6f4; border-radius: 12px; background: #ffffffef; box-shadow: 0 8px 20px #527ba313; }
.workspace-node > span { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 9px; background: #edf3ff; color: var(--oa-primary); }
.workspace-node > div { display: grid; gap: 3px; }.workspace-node strong { font-size: 12px; font-weight: 500; color: #526b85; }.workspace-node small { font-size: 10px; color: #8b9db0; }
.node-knowledge { top: 2px; left: 4px; transform: rotate(-4deg); }.node-collaboration { bottom: 26px; right: -4px; transform: rotate(4deg); }.node-collaboration > span { color: #479689; background: #edf8f5; }
.workspace-visual-caption { position: absolute; bottom: -8px; left: 6px; right: 0; display: flex; align-items: center; justify-content: center; gap: 7px; font-size: 10px; color: #7d95ab; }
@media (max-width: 1350px) { .workspace-launcher { grid-template-columns: minmax(0, 1fr) 220px; padding: 25px; gap: 16px; }.workspace-visual { transform: scale(.82); width: 270px; }.workspace-launcher h2 { font-size: 25px; } }
@media (max-width: 1120px) { .workspace-launcher { grid-template-columns: minmax(0, 1fr); }.workspace-visual { display: none; } }
@media (max-width: 600px) { .workspace-launcher { padding: 20px 16px; }.workspace-launcher h2 { font-size: 23px; line-height: 34px; }.workspace-eyebrow { gap: 2px; flex-direction: column; align-items: flex-start; }.workspace-search-form { gap: 7px; flex-wrap: wrap; }.workspace-search-form input { font-size: 12px; width: calc(100% - 76px); }.workspace-match { width: 100%; justify-content: center; }.workspace-search-symbol { width: 28px; height: 32px; }.workspace-quick-goals { gap: 6px; }.workspace-quick-goals > span { width: 100%; }.workspace-results { max-height: 270px; }.workspace-results-footer > span:last-child { display: none; } }
</style>
