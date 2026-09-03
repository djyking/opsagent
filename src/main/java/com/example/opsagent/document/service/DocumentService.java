package com.example.opsagent.document.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.document.entity.Document;
import com.example.opsagent.document.vo.DocumentChunkVO;
import com.example.opsagent.document.vo.DocumentVO;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 定义工单文档上传、查询、解析和删除能力。
 *
 * @author heyu
 * @since 2026/8/16
 */
public interface DocumentService extends IService<Document> {

    DocumentVO upload(Long ticketId, MultipartFile file);

    List<DocumentVO> listByTicket(Long ticketId);

    DocumentVO detail(Long id);

    DocumentVO parseDocument(Long id);

    List<DocumentChunkVO> listChunks(Long id);

    Document requireAccessibleDocument(Long id);

    void deleteDocument(Long id);
}
