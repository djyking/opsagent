package com.opsagent.rag;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 在现场读取前识别纯交流；混有服务事实或文档要求的问题继续接受证据校验。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AssistantIntent {
    private static final Pattern CONVERSATION =
            Pattern.compile(
                    "(?:(?:你好|您好|嗨|哈喽|早上好|下午好|晚上好|早安|晚安|谢谢你?|再见|好的|收到|"
                            + "在吗|你是谁|你叫什么(?:名字)?|你能做什么|你可以做什么|介绍一下你自己|自我介绍|"
                            + "hello|hi|hey|thanks|thankyou|goodmorning|goodnight)[啊呀哦呢吗吧]*)+");
    private static final Pattern GENERAL =
            Pattern.compile(
                    "什么是|原理|教程|如何|怎么|常见原因|通用|一般如何|介绍|解释|区别|" + "推荐.*(?:Runbook|手册|文档)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENT =
            Pattern.compile("当前|现在|最近|实时|本系统|本项目|我们|现场|该服务|这个服务|该事件|这个事件|这个文档|这份文档|附件|工单");

    private AssistantIntent() {}

    static String body(String question) {
        return question.split("\\n\\n当前页面上下文：", 2)[0].trim();
    }

    static boolean conversation(String question) {
        String text = body(question).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]+", "");
        return CONVERSATION.matcher(text).matches();
    }

    static boolean generalQuestion(String question) {
        String text = body(question);
        return GENERAL.matcher(text).find() && !CURRENT.matcher(text).find();
    }

    static String definitionTopic(String question) {
        String text = body(question);
        var definition =
                Pattern.compile(
                                "^(?:请(?:问|简要)?\\s*)?(?:什么是|何谓|what\\s+is|what\\s+are)\\s*(.+?)(?:[?？。！!；;，,\\n"
                                    + "]|$)",
                                Pattern.CASE_INSENSITIVE)
                        .matcher(text);
        String topic = null;
        if (definition.find()) {
            topic = definition.group(1);
        } else {
            var explanation =
                    Pattern.compile(
                                    "^(?:请)?(?:解释|介绍)(?:一下)?\\s*(.{2,80}?)(?:的)?(?:原理|概念)(?:[?？。！!；;，,\\n"
                                        + "]|$)")
                            .matcher(text);
            if (explanation.find()) topic = explanation.group(1);
        }
        if (topic == null) return null;
        topic = topic.trim().replaceFirst("(?i)^(?:a|an|the)\\s+", "");
        return topic.length() >= 2
                        && topic.length() <= 80
                        && topic.matches("[\\p{L}\\p{N} _+.#/-]+")
                ? topic
                : null;
    }

    static boolean mentionsTopic(String topic, RetrievedChunk chunk) {
        String needle = topic.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String text =
                (chunk.documentName() + " " + chunk.content())
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", "");
        return text.contains(needle);
    }
}
