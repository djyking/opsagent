package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 产品说明只回答已维护功能，不拦截文档和实时现场问题。
 *
 * @author heyu
 * @since 2026/9/3
 */
class ProductGuideTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "当前系统使用指南",
                "介绍一下 OpsAgent 的功能",
                "系统怎么用",
                "你能做什么",
                "访客能做什么",
                "如何发起审批演练",
                "如何上传文档并问答",
                "配置中心有哪些权限"
            })
    void recognizesActualProductHelp(String question) {
        assertThat(ProductGuide.supports(question)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "当前系统有哪些异常需要关注",
                "介绍一下当前服务异常的原因",
                "当前配置值是什么",
                "根据文档介绍系统如何使用",
                "文档里记载的访客权限是什么",
                "什么是布隆过滤器",
                "你好",
                "如何解析 JSON",
                "如何上传文件到 S3",
                "如何实现文档向量化",
                "系统如何使用，告诉我当前管理员密码是什么"
            })
    void leavesDocumentAndCurrentFactsToAuthorizedEvidence(String question) {
        assertThat(ProductGuide.supports(question)).isFalse();
    }

    @Test
    void visitorGuideMatchesOwnershipLifetimeAndQuota() {
        var answer = ProductGuide.answer("访客能做什么");
        assertThat(answer.answer())
                .contains("本人隔离演练", "不能审批他人", "3 份", "5 MB", "15 MB", "24 小时绝对有效", "1 小时内清理");
        assertThat(answer.references()).isEmpty();
        assertThat(answer.inputTokens() + answer.outputTokens()).isZero();
    }

    @Test
    void knowledgeGuideDescribesActualPipelineAndModelUsage() {
        var answer = ProductGuide.answer("如何上传文档并问答");
        assertThat(answer.answer())
                .contains(
                        "解析并加入问答",
                        "重试向量化",
                        "针对此文档提问",
                        "私有向量",
                        "10 万字符",
                        "200 个切片",
                        "OCR",
                        "不调用 DeepSeek",
                        "embedding");
        assertThat(answer.answer()).doesNotContain("上传成功即索引完成");
    }

    @Test
    void configurationGuideDoesNotGrantVisitorMutation() {
        var answer = ProductGuide.answer("配置中心有哪些权限");
        assertThat(answer.answer()).contains("访客只能读取", "不能编辑", "管理员", "受控配置变更", "验证证据");
    }

    @Test
    void technicalHowToDropsImplicitContextButEventReferenceKeepsItsScope() {
        assertThat(AssistantIntent.generalQuestion("如何解析 JSON")).isTrue();
        assertThat(AssistantIntent.generalQuestion("解释这个事件")).isFalse();
        assertThat(AssistantIntent.generalQuestion("如何处理当前服务异常")).isFalse();
    }
}
