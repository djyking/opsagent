package com.example.opsagent.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 统一分页响应对象。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private List<T> records;

    private Long total;

    private Long pageNum;

    private Long pageSize;

    public static <T> PageResponse<T> empty(Long pageNum, Long pageSize) {
        return new PageResponse<>(Collections.emptyList(), 0L, pageNum, pageSize);
    }

    public static <S, T> PageResponse<T> from(IPage<S> page, Function<S, T> converter) {
        List<T> records = page.getRecords().stream().map(converter).toList();
        return new PageResponse<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }
}
