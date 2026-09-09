package com.opsagent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * 发布时人工精选的历史验收案例。固定白名单资源，不按私有记录 ID 动态公开数据。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class PublicCaseService {
    private final List<PublicCaseDtos.CaseView> cases;

    PublicCaseService(ObjectMapper mapper) throws IOException {
        try (var input =
                new ClassPathResource("public-cases/reviewed-cases.json").getInputStream()) {
            cases =
                    List.copyOf(
                            mapper.readValue(
                                    input, new TypeReference<List<PublicCaseDtos.CaseView>>() {}));
        }
    }

    List<PublicCaseDtos.CaseView> list() {
        SecurityUsers.current();
        return cases;
    }

    PublicCaseDtos.CaseView detail(String id) {
        return list().stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "公开案例不存在"));
    }
}
