package com.example.opsagent.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ai_chat_log")
public class AiChatLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String question;

    private String answer;

    private Long documentId;

    private String usedChunks;

    private Long costTimeMs;

    private LocalDateTime createTime;
}
