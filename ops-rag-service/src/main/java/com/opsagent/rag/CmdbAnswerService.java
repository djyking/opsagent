package com.opsagent.rag;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 将目录与依赖类问题路由到真实 CMDB 只读查询，不以文档或模型猜测运行事实。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class CmdbAnswerService {
    private static final Pattern DIRECTORY = Pattern.compile(
            "(?:服务|中间件|组件).*(?:哪些|清单|列表|目录|列出|列一个|有多少|依赖|拓扑|关系)"
                    + "|(?:哪些|清单|列表|列出|有什么|有多少|依赖|拓扑).*(?:服务|中间件|组件)"
                    + "|服务目录|依赖关系|依赖拓扑", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLANATION = Pattern.compile(
            "排查|排障|常见故障|原理|什么是|如何设计|怎么设计|教程|学习|最佳实践|举例|示例|如何实现");
    private final PlatformClient platform;

    CmdbAnswerService(PlatformClient platform) { this.platform = platform; }

    boolean supports(String question, Long documentId) {
        return documentId == null && DIRECTORY.matcher(question).find() && !EXPLANATION.matcher(question).find();
    }

    RagService.Answer answerIfApplicable(String question, Long documentId) {
        if (!supports(question, documentId)) return null;
        long started = System.nanoTime();
        boolean dependencies = question.contains("依赖") || question.contains("拓扑") || question.contains("关系");
        try {
            List<PlatformClient.Ci> all = data(platform.cis());
            List<PlatformClient.Ci> services = all.stream()
                    .filter(ci -> "SERVICE".equals(ci.ciType()))
                    .sorted(Comparator.comparing(ci -> value(ci.ciCode()))).toList();
            StringBuilder text = new StringBuilder();
            List<PlatformClient.Relation> relations = dependencies ? data(platform.relations()) : List.of();
            if (dependencies) appendRelations(text, question, all, relations);
            else {
                boolean middleware = question.contains("中间件") || question.contains("组件");
                List<PlatformClient.Ci> rows = middleware ? all.stream()
                        .filter(ci -> !"SERVICE".equals(ci.ciType()))
                        .sorted(Comparator.comparing(ci -> value(ci.ciCode()))).toList() : services;
                text.append("服务目录当前登记了 **").append(rows.size()).append(" 项")
                        .append(middleware ? "中间件/组件" : "服务").append("**。[S1]\n\n");
                if (!rows.isEmpty()) {
                    text.append("| 名称 | 标识 | 环境 | 目录登记状态 |\n| --- | --- | --- | --- |\n");
                    for (var ci : rows) text.append("| ").append(cell(ci.ciName())).append(" | ")
                            .append(cell(ci.ciCode())).append(" | ").append(cell(ci.environment())).append(" | ")
                            .append(cell(ci.status())).append(" |\n");
                }
            }
            String retrievedAt = Instant.now().toString();
            String updatedAt = java.util.stream.Stream.concat(all.stream().map(PlatformClient.Ci::updateTime),
                            relations.stream().map(PlatformClient.Relation::createTime))
                    .filter(value -> value != null && !value.isBlank()).max(String::compareTo).orElse(null);
            text.append("\n以上来自本次读取的 **CMDB 服务目录")
                    .append(dependencies ? "及依赖登记" : "").append("**，查询时间：")
                    .append(retrievedAt).append("。目录登记状态不代表当前健康；是否可用请查看系统监控。");
            RagService.Source source = new RagService.Source(
                    0, 0, 0, dependencies ? "服务目录与依赖拓扑 · CMDB" : "服务目录 · CMDB",
                    null, null, updatedAt, 0, "S1", "", null, null, null, null,
                    Set.of("CMDB"), false, null,
                    "CMDB", "/itsm/cmdb", updatedAt, retrievedAt);
            return new RagService.Answer(text.toString(), List.of(source), "cmdb", "cmdb-readonly",
                    0, 0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    new RagService.AnswerMetadata("CMDB", false, 0, 0, 0, false, null,
                            true, "structured_data", 0));
        } catch (RuntimeException exception) {
            return new RagService.Answer(
                    "服务目录暂时无法读取，当前不能确认服务清单或依赖关系。请稍后重试，或前往服务目录查看。",
                    List.of(), "cmdb", "cmdb-readonly", 0, 0,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    new RagService.AnswerMetadata("CMDB", false, 0, 0, 0, true, "CMDB_UNAVAILABLE",
                            true, "source_unavailable", 0));
        }
    }

    private void appendRelations(StringBuilder text, String question,
                                 List<PlatformClient.Ci> all, List<PlatformClient.Relation> relations) {
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        List<String> named = all.stream()
                .filter(ci -> mentions(normalized, ci.ciCode()) || mentions(normalized, ci.ciName()))
                .map(PlatformClient.Ci::ciCode).toList();
        if (named.isEmpty() && !globalRelationsQuestion(normalized)) {
            text.append("没有识别到服务目录中对应的服务或组件。请使用目录中的名称或标识，再查询它的依赖。[S1]\n");
            return;
        }
        boolean incoming = question.contains("依赖它") || question.contains("被哪些") || question.contains("谁依赖")
                || question.matches(".*哪些(?:服务|组件|中间件).*依赖.+");
        List<PlatformClient.Relation> selected = relations.stream()
                .filter(relation -> named.isEmpty()
                        || named.contains(incoming ? relation.targetCiCode() : relation.sourceCiCode()))
                .toList();
        text.append("目录中登记的").append(named.isEmpty() ? "" : incoming ? "上游" : "下游")
                .append("依赖关系共 **").append(selected.size()).append(" 条**。[S1]\n\n");
        if (!selected.isEmpty()) {
            text.append("| 依赖方（起点） | 被依赖方（终点） | 登记关系类型 |\n| --- | --- | --- |\n");
            for (var relation : selected) text.append("| ").append(display(all, relation.sourceCiCode()))
                    .append(" | ").append(display(all, relation.targetCiCode())).append(" | ")
                    .append(cell(relation.relationType())).append(" |\n");
        } else text.append("没有找到符合本次范围的已登记关系；这不代表实际系统不存在依赖。\n");
        text.append("\n方向按目录记录的起点 → 终点展示，未自动推断缺失关系或传递依赖。\n");
    }

    private String display(List<PlatformClient.Ci> all, String code) {
        return all.stream().filter(ci -> value(ci.ciCode()).equals(code)).findFirst()
                .map(ci -> cell(ci.ciName()) + "（" + cell(code) + "）").orElse(cell(code));
    }

    private boolean mentions(String query, String term) {
        return term != null && term.length() > 1
                && query.contains(term.toLowerCase(Locale.ROOT).replaceAll("\\s+", ""));
    }

    private boolean globalRelationsQuestion(String question) {
        String plain = question.replaceAll("[\\p{P}\\s]+", "")
                .replaceFirst("^(?:请问|请|帮我|麻烦)?(?:查看|查询|列出|展示|看看)?", "");
        return plain.matches("(?:目前|当前)?(?:系统|平台|全局|全部|所有|整体)?(?:中|内)?(?:登记的)?"
                + "(?:服务|组件|中间件|cmdb)?(?:之间)?(?:的)?(?:依赖关系|依赖拓扑|拓扑)(?:是什么|有哪些|清单|列表)?");
    }

    private <T> List<T> data(KnowledgeClient.Envelope<List<T>> result) {
        if (result == null || result.code() != 0 || result.data() == null) {
            throw new IllegalStateException("CMDB unavailable");
        }
        return result.data();
    }

    private String cell(String value) {
        return value(value).replace("\\", "\\\\").replace("|", "\\|")
                .replace("[", "\\[").replace("]", "\\]").replace("<", "&lt;").replace(">", "&gt;")
                .replace("`", "\\`").replace("*", "\\*").replace("_", "\\_").replaceAll("[\\r\\n]+", " ");
    }

    private String value(String value) { return value == null || value.isBlank() ? "—" : value; }
}
