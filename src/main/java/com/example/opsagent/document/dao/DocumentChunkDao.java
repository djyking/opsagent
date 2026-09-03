package com.example.opsagent.document.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsagent.document.entity.DocumentChunk;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 持久化文档切片并按工单范围读取有限候选集。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Mapper
public interface DocumentChunkDao extends BaseMapper<DocumentChunk> {

    @Select(
            """
            <script>
            SELECT c.*
            FROM document_chunk c
            INNER JOIN document d ON d.id = c.document_id
            WHERE d.ticket_id = #{ticketId}
              AND d.parse_status = 'SUCCESS'
              AND d.deleted = 0
              AND c.deleted = 0
            <if test="documentId != null">
              AND d.id = #{documentId}
            </if>
            ORDER BY d.id, c.chunk_index
            LIMIT #{limit}
            </script>
            """)
    List<DocumentChunk> selectCandidates(
            @Param("ticketId") Long ticketId,
            @Param("documentId") Long documentId,
            @Param("limit") int limit);
}
