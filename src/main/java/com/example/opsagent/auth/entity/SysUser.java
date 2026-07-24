package com.example.opsagent.auth.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NonNull;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NonNull
    private String username;

    private String password;

    /**
     * 昵称
     */
    private String displayName;

    @Pattern(
        regexp = "enable|disable",
        message = "状态值只能是enable和disable"
    )
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 删除标识，0未删除，1已删除
     */
    @TableLogic
    private Integer deleted;
}
