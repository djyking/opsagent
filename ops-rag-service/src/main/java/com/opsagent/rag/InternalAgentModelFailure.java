package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

/**
 * 持久诊断码与用户可执行建议；不包含供应商原文、请求内容或密钥。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class InternalAgentModelFailure {
    private InternalAgentModelFailure() {}

    static String code(AiProviderException failure) {
        return switch (failure.kind()) {
            case TIMEOUT -> "MODEL_TIMEOUT";
            case NETWORK -> "MODEL_NETWORK_ERROR";
            case CANCELLED -> "MODEL_CANCELLED";
            case PROTOCOL -> "MODEL_RESPONSE_INVALID";
            case HTTP -> "MODEL_HTTP_" + failure.statusCode();
            default -> "MODEL_OUTCOME_UNKNOWN";
        };
    }

    static boolean transientFailure(AiProviderException failure) {
        return failure.kind() == AiProviderException.FailureKind.NETWORK
                || failure.kind() == AiProviderException.FailureKind.TIMEOUT
                || (failure.kind() == AiProviderException.FailureKind.HTTP
                    && (failure.statusCode() == 408 || failure.statusCode() == 429 || failure.statusCode() >= 500));
    }

    static BusinessException exception(String code) {
        String reason;
        if (code.equals("MODEL_HTTP_401") || code.equals("MODEL_HTTP_403")) {
            reason = "AI模型鉴权失败，请管理员检查供应商密钥和模型权限后启动新的隔离演练。";
        } else if (code.equals("MODEL_HTTP_429")) {
            reason = "AI供应商限流或额度不足，本轮有限重试未能取得结果；请稍后启动新的隔离演练。";
        } else if (code.startsWith("MODEL_HTTP_5")) {
            reason = "AI供应商暂时不可用，本轮有限重试未能取得结果；请稍后启动新的隔离演练。";
        } else {
            reason = switch (code) {
                case "MODEL_TIMEOUT", "MODEL_HTTP_408" ->
                    "AI模型响应超过本轮时间限额；请检查供应商连通性后启动新的隔离演练。";
                case "MODEL_NETWORK_ERROR" ->
                    "AI模型网络连接失败，本轮有限重试未能取得结果；请检查连通性后启动新的隔离演练。";
                case "MODEL_BUDGET_REJECTED" -> "AI并发或调用额度不足；请待额度恢复后启动新的隔离演练。";
                case "MODEL_CANCELLED" -> "AI模型请求已中断；当前决策未执行工具，请启动新的隔离演练。";
                case "MODEL_RESPONSE_INVALID", "INVALID_NATIVE_TOOL_PAYLOAD", "MODEL_PROTOCOL_UNSUPPORTED" ->
                    "AI模型响应或工具协议无效，未采用该响应执行工具；请检查模型配置后启动新的隔离演练。";
                case "MODEL_NOT_CONFIGURED", "MODEL_SNAPSHOT_CHANGED", "MODEL_TOOL_CALLING_NOT_VERIFIED" ->
                    "AI模型配置缺失、已变更或尚未通过工具能力验证；请管理员核对后启动新的隔离演练。";
                case "MODEL_TOKEN_BUDGET_EXCEEDED" -> "当前模型决策超过剩余Token预算，请缩小任务后重新开始。";
                case "MODEL_CALL_ID_CONFLICT" -> "模型请求与持久记录不一致，请管理员核对运行记录。";
                case "MODEL_RECEIPT_FAILED" -> "模型结果的持久化回执未确认；请管理员检查数据库后启动新的隔离演练。";
                default -> "本轮模型决策未取得可确认的回执，原调用不会重复发送；请核对模型配置后启动新的隔离演练。";
            };
            if (code.startsWith("MODEL_HTTP_") && !code.equals("MODEL_HTTP_408")) {
                reason = "AI供应商不接受本次请求，请管理员检查模型配置和原生工具协议后启动新的隔离演练。";
            }
        }
        return new BusinessException(ErrorCode.CONFLICT, reason + "（诊断码：" + code + "）");
    }
}
