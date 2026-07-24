/*
 * <p>文件名称: GlobalMetaObjectHandler.java</p>
 * <p>项目描述: KOCA 金证云原生平台</p>
 * <p>公司名称: 深圳市金证科技股份有限公司</p>
 * <p>版权所有: (C) 2019-2023</p>
 */

package com.example.opsagent.common.config;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/**
 * 全局实现填入creatime和updatetime
 *
 * @author heyu 
 * @since 2026/7/19
 */
@Component
public class GlobalMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
