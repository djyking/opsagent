package com.example.opsagent.opsagent.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private List<T> records;

    private Long total;

    private Long pageNo;

    private Long pageSize;

    public static <T> PageResponse<T> of(IPage<?> page, List<T> records) {
        return new PageResponse<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }
}
