package com.example.opsagent.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 映射工单文档问答的成功或失败处理记录。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
@TableName("ai_chat_log")
public class AiChatLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    private Long documentId;

    private Long userId;

    private String question;

    private String answer;

    private String modelName;

    private Integer promptTokens;

    private Integer completionTokens;

    private String status;

    private String errorMessage;

    private Long costTimeMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
