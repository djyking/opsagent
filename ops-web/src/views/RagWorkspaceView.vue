<script setup lang="ts">
import { ArrowRight, BookOpen, Bot, Clock3, Copy, History, PanelRightClose, PanelRightOpen, Pencil, Plus, RotateCw, Send, Sparkles, Trash2 } from '@lucide/vue';
import { ragAnswerLabel } from '@/api/rag-stream';
import { useRagConversations } from '@/composables/useRagConversations';
import AnswerContent from '@/components/AnswerContent.vue';
import RagSources from '@/components/RagSources.vue';
import PageHeader from '@/components/PageHeader.vue';
import BaseModal from '@/components/BaseModal.vue';
const { question, sessions, sessionId, turns, total, hasEarlier, loading, busy, historyError, error, selectedTurnId, historyOpen, contextOpen, editMode, editTitle, actionBusy, chatScroll, current, referenceTurn, references, refreshHistory, selectSession, earlier, newSession, ask, turnLabel, manageSession, copyAnswer } = useRagConversations();
const suggestions = ['排查 Redis 连接超时', '分析 RabbitMQ 消息堆积', '查询 SLA 超时处理规范'];
</script>

<template>
  <div class="stack-page rag-page">
    <PageHeader title="智能问答" description="基于知识库检索来源，辅助分析运维问题" />
    <section class="rag-three-pane" :class="{ 'context-closed': !contextOpen, 'history-open': historyOpen }">
      <aside class="rag-conversations" aria-label="我的会话">
        <header class="panel-header"><div><h3>我的会话</h3><span class="panel-count">{{ total }} 个 · 仅当前账号可见</span></div><button class="icon-button" aria-label="新会话" :disabled="busy" @click="newSession"><Plus :size="15" /></button></header>
        <button v-if="!sessionId" class="rag-session active" aria-current="true"><Bot :size="16" /><span><strong>新会话</strong><small>发送后自动保存</small></span></button>
        <button v-for="session in sessions" :key="session.id" class="rag-session" :class="{ active: session.id === sessionId }" :aria-current="session.id === sessionId ? 'true' : undefined" :disabled="busy" @click="selectSession(session.id)"><Bot :size="16" /><span><strong>{{ session.title }}</strong><small>{{ new Date(session.updateTime).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }}</small></span></button>
        <button v-if="sessions.length < total" class="button secondary rag-history-more" @click="refreshHistory(true)">加载更多会话</button>
        <p v-if="historyError" class="inline-error rag-history-error">{{ historyError }}</p>
        <button class="rag-library-link" :disabled="busy" @click="refreshHistory()"><RotateCw :size="15" />刷新会话列表</button>
        <RouterLink class="rag-library-link" to="/knowledge"><BookOpen :size="16" /><span>管理知识库</span><ArrowRight :size="14" /></RouterLink>
        <p class="rag-history-notice">会话按账号保存，角色不共享聊天记录</p>
      </aside>
      <main class="ai-panel rag-workspace">
        <header class="panel-header">
          <div class="rag-current-title"><h3><Sparkles :size="15" />{{ current?.title || '智能知识问答' }}</h3></div>
          <div class="rag-header-actions">
            <button class="icon-button rag-history-toggle" :aria-expanded="historyOpen" aria-label="显示历史会话" @click="historyOpen = !historyOpen"><History :size="17" /></button>
            <button v-if="sessionId" class="icon-button" aria-label="重命名会话" :disabled="busy" @click="editTitle = current?.title || ''; editMode = 'rename'"><Pencil :size="15" /></button>
            <button v-if="sessionId" class="icon-button" aria-label="删除当前会话" :disabled="busy" @click="editMode = 'delete'"><Trash2 :size="15" /></button>
            <button class="button secondary" aria-label="新会话" :disabled="busy" @click="newSession"><Plus :size="15" />新会话</button>
            <button class="icon-button rag-context-toggle" :aria-expanded="contextOpen" :aria-label="contextOpen ? '收起检索上下文' : '展开检索上下文'" @click="contextOpen = !contextOpen"><PanelRightClose v-if="contextOpen" :size="17" /><PanelRightOpen v-else :size="17" /></button>
          </div>
        </header>
        <div ref="chatScroll" class="rag-chat-scroll" :aria-busy="busy || loading">
          <button v-if="hasEarlier" class="button secondary" :disabled="loading || busy" @click="earlier">加载更早的消息</button>
          <p v-if="loading" class="rag-context-note">正在读取会话…</p>
          <article v-for="turn in turns" :key="turn.id" class="rag-turn">
            <div class="rag-user-message"><strong>你</strong><p>{{ turn.question }}</p></div>
            <div class="stream-answer">
              <div class="answer-status"><span :class="{ pulse: busy && turn.status === 'PROCESSING' }"><Bot :size="18" /></span><div><strong>{{ turnLabel(turn) }}</strong><small v-if="turn.result"><Clock3 :size="13" />{{ ragAnswerLabel(turn.result) }} · {{ turn.result.latencyMs }} ms</small></div><div v-if="turn.answer && turn.status !== 'PROCESSING'" class="rag-answer-actions"><button class="icon-button" aria-label="复制回答" @click="copyAnswer(turn)"><Copy :size="15" /></button><button class="icon-button" aria-label="重新提问" :disabled="busy" @click="ask(turn.question)"><RotateCw :size="15" /></button></div></div>
              <AnswerContent v-if="turn.answer" :content="turn.answer" />
              <div v-else-if="turn.status === 'PROCESSING'" class="answer-skeleton"><i /><i /><i /></div>
              <p v-if="turn.errorMessage" class="rag-incomplete" role="status">{{ turn.errorMessage }}</p>
              <button v-if="turn.result?.references.length" class="rag-show-sources" @click="selectedTurnId = turn.id; contextOpen = true"><BookOpen :size="14" />查看本条回答的 {{ turn.result.references.length }} 条来源</button>
            </div>
          </article>
          <div v-if="!turns.length && !loading" class="rag-empty"><span class="rag-empty-icon"><Sparkles :size="28" /></span><small class="rag-welcome-label">从一个问题开始</small><strong>一起找到问题的下一步。</strong><p>描述你遇到的现象，我会检索知识库，<br />把相关来源和分析建议放在一起。</p><div class="suggested-prompts"><button v-for="item in suggestions" :key="item" :disabled="busy" @click="ask(item)"><BookOpen :size="16" /><span>{{ item }}</span><ArrowRight class="suggested-arrow" :size="15" /></button></div></div>
          <p v-if="error" class="inline-error rag-error" role="alert">{{ error }}</p>
        </div>
        <form class="rag-question-form chat-composer" @submit.prevent="ask()"><textarea v-model="question" required rows="3" maxlength="2000" aria-label="运维问题" :placeholder="sessionId ? '继续追问，或开始新的问题…' : '输入运维问题…'" @keydown.enter.exact.prevent="ask()" /><div class="question-submit-row"><span class="knowledge-scope"><BookOpen :size="14" />知识库</span><span class="composer-hint">Enter 发送 · Shift + Enter 换行</span><button class="composer-send" :disabled="busy || loading || !question.trim()" :title="busy ? '生成中' : '发送问题'" aria-label="发送问题"><Send :size="16" /></button></div></form>
      </main>
      <aside v-if="contextOpen" class="rag-context-panel"><header class="panel-header"><div><h3>检索上下文</h3><span class="panel-count">{{ references.length }} 条来源</span></div></header><p class="rag-context-note">{{ referenceTurn ? '对应问题：' + referenceTurn.question : '先查看引用，再结合实际环境核对建议。' }}</p><RagSources :references="references" /><div v-if="!references.length" class="rag-context-empty"><BookOpen :size="24" /><strong>让答案有据可查</strong><p>完成提问后，这里显示命中的知识文档、片段与相关度。</p></div></aside>
    </section>
    <BaseModal v-if="editMode" :title="editMode === 'rename' ? '重命名会话' : '删除会话'" @close="!actionBusy && (editMode = '')"><form id="manage-conversation" @submit.prevent="manageSession"><label v-if="editMode === 'rename'">会话名称<input v-model.trim="editTitle" required maxlength="120" /></label><p v-else>删除“{{ current?.title }}”后，该会话及其问答将从历史列表移除。其他会话不受影响。</p><p v-if="error" class="inline-error">{{ error }}</p></form><template #footer><button class="button secondary" :disabled="actionBusy" @click="editMode = ''">取消</button><button class="button primary" form="manage-conversation" :disabled="actionBusy || (editMode === 'rename' && !editTitle.trim())">{{ actionBusy ? '处理中…' : editMode === 'rename' ? '保存名称' : '确认删除' }}</button></template></BaseModal>
  </div>
</template>
