package com.opsagent.common.core;

import java.util.List;

/** 通用分页结果，避免业务服务直接暴露持久化框架的分页对象。 */
public record PageResult<T>(List<T> records, long total, long pageNum, long pageSize) {
    public PageResult {
        records = List.copyOf(records);
    }
}
