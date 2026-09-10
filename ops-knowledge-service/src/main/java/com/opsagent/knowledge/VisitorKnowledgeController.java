package com.opsagent.knowledge;

import com.opsagent.common.core.ApiResponse;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Private visitor endpoints, separate from publication and administrator operations.
 *
 * @author heyu
 */
@RestController
@RequestMapping("/api/knowledge/experience")
class VisitorKnowledgeController {
    private final VisitorKnowledgeService service;

    VisitorKnowledgeController(VisitorKnowledgeService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.success(service.overview());
    }

    @PostMapping("/documents")
    ApiResponse<Long> upload(
            @RequestPart MultipartFile file, @RequestParam(required = false) Long ticketId) {
        return ApiResponse.success(service.upload(file, ticketId));
    }

    @PostMapping("/documents/{id}/parse")
    ApiResponse<Void> parse(@PathVariable long id) {
        service.requestProcess(id, false);
        return ApiResponse.success(null);
    }

    @PostMapping("/documents/{id}/index")
    ApiResponse<Void> index(@PathVariable long id) {
        service.requestProcess(id, true);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/documents/{id}")
    ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.success(null);
    }
}
