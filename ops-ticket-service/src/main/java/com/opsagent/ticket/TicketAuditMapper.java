package com.opsagent.ticket;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单评论和状态流转历史的数据访问接口。
 *
 * @author heyu
 * @since 2026/8/9
 */
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

    @Insert(
            "INSERT INTO ticket_assignment(ticket_id,assignee_id,assigned_by,assignment_type,create_time)"
                    + " VALUES(#{ticketId},#{assigneeId},#{assignedBy},#{type},NOW())")
    int assignment(long ticketId, long assigneeId, long assignedBy, String type);

    @Insert(
            "INSERT INTO ticket_operation_log(ticket_id,operator_id,operation,request_id,detail_json,create_time)"
                    + " VALUES(#{ticketId},#{operatorId},#{operation},#{requestId},#{detail},NOW())")
    int operation(
            long ticketId,
            long operatorId,
            String operation,
            String requestId,
            String detail);

    @Select(
            "SELECT id,ticket_id ticketId,user_id userId,content,create_time createTime FROM"
                    + " ticket_comment WHERE ticket_id=#{ticketId} AND deleted=0 ORDER BY id")
    List<Comment> comments(long ticketId);

    /**
     * 工单状态流转历史数据。
     *
     * @author heyu
     * @since 2026/8/9
     */
    record History(
            long id,
            long ticketId,
            long operatorId,
            String operationType,
            String fromStatus,
            String toStatus,
            String remark,
            LocalDateTime createTime) {}

    /**
     * 工单评论数据。
     *
     * @author heyu
     * @since 2026/8/9
     */
    record Comment(long id, long ticketId, long userId, String content, LocalDateTime createTime) {}
}
