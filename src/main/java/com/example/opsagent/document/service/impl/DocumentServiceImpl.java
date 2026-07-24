package com.example.opsagent.document.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.document.dto.DocumentQueryRequest;
import com.example.opsagent.document.entity.Document;
import com.example.opsagent.document.dao.DocumentDao;
import com.example.opsagent.document.service.DocumentService;
import com.example.opsagent.document.vo.DocumentChunkVO;
import com.example.opsagent.document.vo.DocumentVO;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentServiceImpl extends ServiceImpl<DocumentDao, Document> implements DocumentService {

    @Override
    public DocumentVO upload(MultipartFile file, String uploader) {
        return new DocumentVO();
    }

    @Override
    public DocumentVO detail(Long id) {
        return new DocumentVO();
    }

    @Override
    public PageResponse<DocumentVO> pageDocuments(DocumentQueryRequest request) {
        return PageResponse.empty(request.getPageNum(), request.getPageSize());
    }

    @Override
    public DocumentVO parseDocument(Long id) {
        return new DocumentVO();
    }

    @Override
    public List<DocumentChunkVO> listChunks(Long id) {
        return Collections.emptyList();
    }

    @Override
    public void deleteDocument(Long id) {
        removeById(id);
    }
}
