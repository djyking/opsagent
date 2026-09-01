package com.opsagent.auth;
import com.baomidou.mybatisplus.annotation.*;
@TableName("sys_user") public class User {@TableId(type=IdType.AUTO)private Long id;private String username;private String password;private String displayName;private String status;@TableLogic private Integer deleted;
 public Long getId(){return id;}public String getUsername(){return username;}public String getPassword(){return password;}public String getDisplayName(){return displayName;}public String getStatus(){return status;}public Integer getDeleted(){return deleted;}}
