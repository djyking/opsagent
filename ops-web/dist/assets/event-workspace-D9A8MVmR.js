const o={QUEUED:"准备执行",RUNNING:"执行中",WAITING_APPROVAL:"等待审批",WAITING_INPUT:"等待补充信息",PAUSED:"已暂停",COMPLETED:"流程结束",NEEDS_ATTENTION:"需要人工处理",CANCELLED:"已取消",EXPIRED:"已到期",REJECTED:"审批拒绝",BUDGET_EXCEEDED:"预算已达上限"};function u(e){if(e.recordType==="EVENT_RESULT"&&e.createBy===0&&e.evidence)try{const n=JSON.parse(e.evidence);if(n&&typeof n=="object"&&!Array.isArray(n)&&"source"in n&&n.source==="AI_MACHINE_RESULT")return"AI 自动登记"}catch{}return`用户 #${e.createBy}`}function c(e){return{AGENT_TOOL:"Agent 工具执行",MANUAL:"人工恢复",TTL_GUARD:"到期保护恢复",NOT_APPLIED:"故障未生效"}[e]||"尚无明确恢复来源"}function E(e,n=Date.now()){if(!e)return!1;const i=Date.parse(e.verification.observedAt||""),s=Date.parse(e.generatedAt);return Number.isFinite(i)&&Number.isFinite(s)&&n-i<2e4&&i-n<=2e3&&n-s<2e4&&s-n<=2e3}function $(e){const n=e;return!!n&&([400,401,403,404,422].includes(n.status||0)||[4e4,40100,40300,40400].includes(n.code||0))}function a(e,n){const i=(s,t)=>s.length?s.map(r=>`- ${r}`).join(`
`):`- ${t}`;return`# ${n} · 事件复盘草稿

> 待人工核对与审核。本文由已保存的事件资料整理，不代表新的根因确认。

事件编号：${e.ticketId}
目标服务：${e.targetCode||"未关联"}
生成时间：${e.generatedAt}

## 症状与事实
${i(e.facts.map(s=>`${s.label}：${s.value}（来源：${s.source}；时间：${s.observedAt||"未提供"}；范围：${s.scope}）`),"暂无可读取事实")}

## 诊断判断
${e.diagnosis.summary||"尚无已记录的诊断结论"}
${i(e.diagnosis.candidateCauses,"候选原因尚未确认")}

## 相关变更
${i(e.changes.map(s=>`${s.summary}（${s.observedAt||"时间未提供"}，${s.status}）`),"暂无可读取的相关变更")}
变更时间接近故障不等于已证明因果关系。

## 处置运行
${i(e.runs.map(s=>`${s.id}：${o[s.status]||s.status}；${s.message||"详见对应执行记录"}`),"暂无当前账号可读取的运行")}

## 恢复验证
- 判定：${e.verification.label}
- 证据范围：${e.verification.scope==="CURRENT"?"当前事件观测":e.verification.scope==="HISTORICAL"?"历史恢复记录，不代表当前健康":"尚无验证证据"}
- 恢复来源：${c(e.verification.source)}
- 验证时间：${e.verification.observedAt||"未提供"}

## 证据缺口与适用条件
${i([...new Set([...e.gaps.map(s=>s.message),...e.diagnosis.evidenceGaps])],"请补充适用版本、环境差异和人工核对意见")}
`}export{u as a,a as b,$ as d,o as e,E as o,c as r};
