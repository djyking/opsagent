package com.example.opsagent.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 映射问答记录与引用文档切片的相关性关系。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
@TableName("ai_qa_reference")
public class AiQaReference {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long qaRecordId;

    private Long chunkId;

    private Double relevanceScore;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
