package com.example.opsagent.common.api;

import java.util.Collections;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
