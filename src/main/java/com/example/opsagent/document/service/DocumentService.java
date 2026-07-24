package com.example.opsagent.document.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.document.dto.DocumentQueryRequest;
import com.example.opsagent.document.entity.Document;
import com.example.opsagent.document.vo.DocumentChunkVO;
import com.example.opsagent.document.vo.DocumentVO;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService extends IService<Document> {

    DocumentVO upload(MultipartFile file, String uploader);

    DocumentVO detail(Long id);

    PageResponse<DocumentVO> pageDocuments(DocumentQueryRequest request);

    DocumentVO parseDocument(Long id);

    List<DocumentChunkVO> listChunks(Long id);

    void deleteDocument(Long id);
}
