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

    @Insert(
            "INSERT INTO ticket_work_record(ticket_id,record_type,content,evidence,create_by,"
                    + "create_time) VALUES(#{ticketId},#{recordType},#{content},#{evidence},"
                    + "#{createBy},NOW())")
    int workRecord(
            long ticketId,
            String recordType,
            String content,
            String evidence,
            long createBy);

    @Select(
            "SELECT id,ticket_id ticketId,user_id userId,content,create_time createTime FROM"
                    + " ticket_comment WHERE ticket_id=#{ticketId} AND deleted=0 ORDER BY id")
    List<Comment> comments(long ticketId);

    @Select(
            "SELECT id,ticket_id ticketId,assignee_id assigneeId,assigned_by assignedBy,"
                    + "assignment_type assignmentType,create_time createTime FROM"
                    + " ticket_assignment WHERE ticket_id=#{ticketId} ORDER BY id")
    List<Assignment> assignments(long ticketId);

    @Select(
            "SELECT id,ticket_id ticketId,operator_id operatorId,operation,request_id requestId,"
                    + "detail_json detailJson,create_time createTime FROM ticket_operation_log"
                    + " WHERE ticket_id=#{ticketId} ORDER BY id")
    List<Operation> operations(long ticketId);

    @Select(
            "SELECT id,ticket_id ticketId,record_type recordType,content,evidence,create_by"
                    + " createBy,create_time createTime FROM ticket_work_record"
                    + " WHERE ticket_id=#{ticketId} ORDER BY id")
    List<WorkRecord> workRecords(long ticketId);

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

    /**
     * 工单分派轨迹。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record Assignment(
            long id,
            long ticketId,
            long assigneeId,
            long assignedBy,
            String assignmentType,
            LocalDateTime createTime) {}

    /**
     * 工单服务内部操作日志。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record Operation(
            long id,
            long ticketId,
            long operatorId,
            String operation,
            String requestId,
            String detailJson,
            LocalDateTime createTime) {}

    /**
     * 工单结构化处置记录。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record WorkRecord(
            long id,
            long ticketId,
            String recordType,
            String content,
            String evidence,
            long createBy,
            LocalDateTime createTime) {}
}
