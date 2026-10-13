package com.opsagent.rag;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 随当前产品维护的使用说明；不把功能说明伪装成知识库引用或实时现场事实。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class ProductGuide {
    private static final Pattern REQUEST =
            Pattern.compile(
                    "(?:系统|平台|OpsAgent).*(?:怎么用|如何使用|使用指南|使用说明|功能介绍|有什么功能|有哪些功能|能做什么|怎么开始)"
                        + "|(?:介绍|了解).*(?:系统|平台|OpsAgent).*(?:功能|使用)?"
                        + "|^(?:系统使用指南|功能介绍|使用指南|你能做什么|你可以做什么)[？?。！!]*$"
                        + "|访客.*(?:能做什么|能用什么|权限|如何使用|怎么用|可以.*(?:审批|上传|操作)|有效期|多久|额度)"
                        + "|(?:怎么|如何).*(?:发起|启动|审批|体验).*(?:演练|自动化)"
                        + "|(?:怎么|如何).*(?:上传.*(?:文档|资料)|解析.*文档|文档.*(?:解析|切片|向量化|问答)|查看切片|私有问答|体验库)"
                        + "|(?:配置中心|配置变更).*(?:怎么用|如何使用|权限|谁能|只读|管理员)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern FACT =
            Pattern.compile(
                    "(?:如何|怎么)(?:设计|实现)|(?:当前|现在|实时|最近).*(?:故障|异常|健康|日志|负载|恢复了吗|运行状态|配置值)"
                            + "|(?:文档|附件|工单)(?:中|里|内容|记载|写|说)|根据.*(?:文档|附件|工单)"
                            + "|(?:密码|密钥|令牌|token|api.key).*(?:多少|是什么|告诉|给我|查询|输出)",
                    Pattern.CASE_INSENSITIVE);

    private static final String OVERVIEW =
            """
            可以从这些入口开始：
            - [运行总览](/dashboard)：看服务与事件概况，再进入具体对象核对详情。
            - [服务观测](/observability/topology)：查看拓扑、服务目录、指标与采集、配置中心和流量治理；现场内容以页面实际采集时间与证据为准。
            - [事件处置](/tickets)：查看有权访问的事件与当前处置阶段，证据、诊断和历史按需展开。
            - [自动化中心](/automation)：阅读公共案例，或在“本人演练”发起隔离演练，跟踪诊断、人工审批和恢复验证。
            - [知识与经验](/knowledge)：阅读公共知识；访客在“我的体验库”上传本人文件并完成解析、切片、向量化和文档问答。
            - [智能问答](/rag/chat)：也可用页面右侧助手；问候直接交流，通用问题优先检索授权知识，当前现场问题按授权证据回答。

            第一次使用建议先看公共案例，再做一次本人演练，最后上传一份小文档验证问答。可以继续问“访客能做什么”“如何发起审批演练”“如何上传文档并问答”或“配置中心有哪些权限”。
            """;
    private static final String VISITOR =
            """
            访客使用独立体验身份，可阅读公共案例、授权公共知识及脱敏的服务/配置视图，并可完成本人隔离演练和私有知识体验。
            - 本人演练：可发起、查看进度、审批本人隔离演练的待审批动作，并核对恢复；不能审批他人的任务，也不能操作真实生产配置。
            - 本人资料：1 个“我的体验库”，最多 3 份文件；每份 5 MB、合计 15 MB，仅本人可见和检索。
            - 体验身份从创建起 24 小时绝对有效；普通退出后重新进入可继续原体验，上传不会延长有效期。到期或主动结束体验立即禁止访问，资料与向量随后 1 小时内清理。
            - 管理员拥有管理、审计及受控变更等授权入口；普通账号、运维与访客的操作范围由实际角色和资源归属共同校验，看到公共案例不代表拥有案例原任务权限。

            入口：[自动化中心](/automation) · [我的体验库所在页面](/knowledge)。
            """;
    private static final String DRILL =
            """
            1. 打开[自动化中心](/automation)，切换“本人演练”，选择可用演练场景并发起，阅读影响说明后确认。
            2. 进入本次运行查看诊断和当前步骤。出现“待审批”时，通过运行页或全局审批弹窗核对本次动作与参数，再批准或拒绝；访客只能决定本人隔离演练的审批。
            3. 批准后继续查看实际执行回执与恢复验证。模型调用或工具执行失败会保留原因；需要人工处理时，按页面提示执行恢复，再核对证据。
            4. 到关联事件完成页面要求的技术确认、业务确认与关闭；批准动作或出现成功提示不代表事件已经关闭。

            公共案例用于阅读已脱敏的示例过程；运行记录、本人审批与恢复操作在本人的任务范围内。不能通过助手一句话跳过人工审批或直接修改生产环境。
            """;
    private static final String KNOWLEDGE =
            """
            访客完整体验路径：
            1. 打开[知识与经验](/knowledge)，选择“我的体验库”→“上传文档”。支持 PDF、DOCX、TXT、Markdown；最多 3 份，每份 5 MB，总量 15 MB。
            2. 点击“解析并加入问答”，系统依次提取文本、生成切片并建立私有向量索引。每份最多 10 万字符、200 个切片；扫描 PDF 暂不做 OCR，超限或无可解析文本会说明原因。
            3. 在详情中“查看切片”，核对真实内容。失败可按阶段“重试解析”或“重试向量化”；每个体验身份同时处理 1 份文件。
            4. 向量化完成后点“针对此文档提问”。该次问答只使用你有权读取的所选文档；无匹配会说明资料不足，不用别人的文档补造引用。

            公共知识保持只读。“加入问答”不会把你的资料发布到公共库；普通问答可检索授权公共知识和本人体验资料。\
            解析切片不调用 DeepSeek，向量化调用 embedding，答案生成调用所选模型；实际用量缺失时标记未知，不把预算预留当实际费用。
            体验身份到期或主动结束后立即失去访问权，文件、切片与向量随后 1 小时内清理。
            """;
    private static final String CONFIGURATION =
            """
            打开[配置中心](/observability/config)，先选择目录项查看配置摘要；复杂值可展开查看或复制，敏感字段按权限脱敏。
            访客只能读取授权配置，不能编辑、发布、重启生产服务或审批管理员变更。管理员在具备权限的配置项中，\
            按[受控配置变更](/observability/config/managed)页面准备变更、核对差异、完成审批并执行；\
            是否需要重启及是否已生效，以该次任务的执行结果与验证证据为准。
            配置中心展示值不等于每个服务的运行时配置已经刷新。助手可以解释流程，但不能仅凭通用知识宣称当前配置已生效，也不会自动替你提交变更。
            """;

    private ProductGuide() {}

    static boolean supports(String question) {
        String text = AssistantIntent.body(question);
        return REQUEST.matcher(text).find() && !FACT.matcher(text).find();
    }

    static RagService.Answer answer(String question) {
        String text = AssistantIntent.body(question);
        String content;
        if (text.matches("(?s).*(?:上传|解析|切片|向量|知识|文档问答|私有问答|体验库).*")) {
            content = KNOWLEDGE;
        } else if (text.matches("(?s).*(?:配置中心|配置变更).*")) {
            content = CONFIGURATION;
        } else if (text.matches("(?s).*(?:演练|自动化).*")) {
            content = DRILL;
        } else if (text.contains("访客")) {
            content = VISITOR;
        } else {
            content = OVERVIEW;
        }
        return new RagService.Answer(
                "系统使用指南（随当前版本维护的功能说明，未读取实时现场数据）：\n\n" + content,
                List.of(),
                "system",
                "product-guide",
                0,
                0,
                0,
                new RagService.AnswerMetadata(
                        "PRODUCT_GUIDE",
                        false,
                        0,
                        0,
                        0,
                        false,
                        null,
                        true,
                        "product_guide",
                        0,
                        AssistantTokenBudget.LIMIT,
                        0,
                        true,
                        0));
    }
}
