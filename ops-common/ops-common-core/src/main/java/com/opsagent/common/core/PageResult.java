package com.opsagent.common.core;
import java.util.List;
public record PageResult<T>(List<T> records,long total,long pageNum,long pageSize){public PageResult{records=List.copyOf(records);}}
