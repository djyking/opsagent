export type IllustrationKind = "alert" | "rag" | "knowledge" | "index" | "audit";
export interface ExperienceStory { key: string; label: string; title: string; description: string; tags: string[]; illustration: IllustrationKind; to?: string; action?: string; admin?: boolean }
export const authStories: ExperienceStory[] = [
  { key: "alert", label: "告警联动", title: "让每一次告警，都进入可追踪的处理链路。", description: "从告警关联到责任人分配，把工单、服务与处置记录串在一起。", tags: ["告警联动", "工单闭环", "状态追踪"], illustration: "alert" },
  { key: "rag", label: "可信问答", title: "让每一个答案，都带着可以核对的来源。", description: "从运维文档中检索相关片段，在回答旁查看引用，继续追溯原文。", tags: ["知识检索", "引用来源", "流式回答"], illustration: "rag" },
  { key: "audit", label: "流程治理", title: "让处置不仅完成，也能够被验证、被审计。", description: "记录诊断依据、执行动作与验证结论，让经验成为下一次处理的起点。", tags: ["SLA", "操作审计", "知识发布"], illustration: "audit" },
];
export const capabilityStories: ExperienceStory[] = [
  { key: "rag", label: "可信问答", title: "让答案带着来源出现", description: "先检索知识，再基于命中片段回答。展开来源，核对每一条建议的依据。", tags: ["知识检索", "引用来源", "流式回答"], illustration: "rag", to: "/rag/chat", action: "开始智能问答" },
  { key: "knowledge", label: "知识治理", title: "把运维文档变成可复用的知识", description: "从上传、解析到审核发布，在清晰的状态中持续维护团队知识。", tags: ["文档解析", "结构化切片", "审核发布"], illustration: "knowledge", to: "/knowledge", action: "进入知识库" },
  { key: "index", label: "索引运维", title: "让知识的检索状态有据可查", description: "核对 Elasticsearch 与 Qdrant 的索引一致性，定位失败任务并发起修复。", tags: ["一致性检查", "失败任务", "可控修复"], illustration: "index", to: "/knowledge/index-admin", action: "查看索引健康", admin: true },
];
export const capabilities = [
  { key: "rag", label: "智能问答", description: "排查连接超时、消息堆积或 SLA 规范，基于知识来源获取建议。", detail: "回答会展示实际检索来源。模型暂不可用时会明确退回原文检索，请结合环境核验建议。", tags: ["检索", "引用", "流式回答"], to: "/rag/chat", tone: "blue", admin: false },
  { key: "knowledge", label: "知识库", description: "上传故障手册、查看解析切片，维护可审核的运维文档。", detail: "先创建或选择知识库，再上传文档并解析。提交审核后由管理员决定是否发布。", tags: ["文档", "切片", "发布"], to: "/knowledge", tone: "green", admin: false },
  { key: "review", label: "知识审核", description: "复核待发布知识与审核意见，守住检索内容的质量。", detail: "仅管理员可审核。通过后进入发布流程；驳回时需说明原因，便于维护人修订。", tags: ["审核", "意见", "追踪"], to: "/knowledge/review", tone: "amber", admin: true },
  { key: "index", label: "索引管理", description: "检查索引一致性与失败任务，按文档执行有依据的修复。", detail: "数量与状态来自后端一致性检查。单文档修复提交任务，全量重建需要单独确认。", tags: ["ES", "Qdrant", "修复"], to: "/knowledge/index-admin", tone: "purple", admin: true },
];
