package com.opsagent.ticket;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/** 工单评论和状态流转历史的数据访问接口。 */
public interface TicketAuditMapper {
    @Insert(
            "INSERT INTO"
                + " ticket_history(ticket_id,operator_id,operation_type,from_status,to_status,remark,create_time)"
                + " VALUES(#{ticketId},#{operatorId},#{operation},#{from},#{to},#{remark},NOW())")
    int history(
            long ticketId,
            long operatorId,
            String operation,
            String from,
            String to,
            String remark);

    @Select(
            "SELECT id,ticket_id ticketId,operator_id operatorId,operation_type"
                + " operationType,from_status fromStatus,to_status toStatus,remark,create_time"
                + " createTime FROM ticket_history WHERE ticket_id=#{ticketId} ORDER BY id")
    List<History> historyList(long ticketId);

    @Insert(
            "INSERT INTO ticket_comment(ticket_id,user_id,content,create_time,update_time)"
                    + " VALUES(#{ticketId},#{userId},#{content},NOW(),NOW())")
    int comment(long ticketId, long userId, String content);

    @Select(
            "SELECT id,ticket_id ticketId,user_id userId,content,create_time createTime FROM"
                    + " ticket_comment WHERE ticket_id=#{ticketId} AND deleted=0 ORDER BY id")
    List<Comment> comments(long ticketId);

    record History(
            long id,
            long ticketId,
            long operatorId,
            String operationType,
            String fromStatus,
            String toStatus,
            String remark,
            LocalDateTime createTime) {}

    record Comment(long id, long ticketId, long userId, String content, LocalDateTime createTime) {}
}
