package com.opsagent.ticket;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 持久化工单 SLA 计时状态和幂等事件。
 *
 * @author heyu
 * @since 2026/9/3
 */
public interface SlaMapper {
    String PAGE_FILTER = """
            FROM ticket_sla s JOIN ticket t ON t.id=s.ticket_id
            WHERE t.deleted=0
            <if test="query.view == 'risk'">
              AND s.resolution_status='RUNNING'
              AND s.resolution_deadline &gt; #{now} AND s.resolution_deadline &lt;= #{riskUntil}
            </if>
            <if test="query.view == 'breached'">AND s.resolution_status='BREACHED'</if>
            <if test="query.priority != ''">AND t.priority=#{query.priority}</if>
            <if test="query.service != ''">AND t.affected_ci_code=#{query.service}</if>
            <if test="query.keyword != ''">
              AND (LOWER(t.title) LIKE LOWER(#{query.keywordPattern}) ESCAPE '!'
                OR LOWER(t.ticket_no) LIKE LOWER(#{query.keywordPattern}) ESCAPE '!'
                OR LOWER(t.affected_ci_code) LIKE LOWER(#{query.keywordPattern}) ESCAPE '!')
            </if>
            """;

    @Insert(
            """
            INSERT IGNORE INTO ticket_sla(
                ticket_id,policy_id,response_deadline,resolution_deadline,
                response_status,resolution_status,escalation_level,next_check_time,
                version,create_time,update_time)
            SELECT #{ticketId},id,
                   DATE_ADD(NOW(),INTERVAL response_minutes MINUTE),
                   DATE_ADD(NOW(),INTERVAL resolution_minutes MINUTE),
                   'RUNNING','RUNNING',0,
                   DATE_ADD(NOW(),INTERVAL GREATEST(1,response_minutes*warning_percent/100) MINUTE),
                   0,NOW(),NOW()
            FROM sla_policy WHERE priority=#{priority} AND enabled=1 LIMIT 1
            """)
    int start(long ticketId, String priority);

    @Update(
            """
            UPDATE ticket_sla SET response_status='COMPLETED',response_time=NOW(),
                next_check_time=LEAST(resolution_deadline,
                    DATE_ADD(create_time,INTERVAL (
                        SELECT GREATEST(1,resolution_minutes*warning_percent/100)
                        FROM sla_policy WHERE id=ticket_sla.policy_id) MINUTE)),
                version=version+1,update_time=NOW()
            WHERE ticket_id=#{ticketId} AND response_status IN ('RUNNING','BREACHED')
            """)
    int responseCompleted(long ticketId);

    @Update(
            """
            UPDATE ticket_sla SET resolution_status='COMPLETED',resolution_time=NOW(),
                next_check_time=NULL,version=version+1,update_time=NOW()
            WHERE ticket_id=#{ticketId} AND resolution_status IN ('RUNNING','BREACHED')
            """)
    int resolutionCompleted(long ticketId);

    @Select(
            """
            SELECT s.id,s.ticket_id ticketId,s.response_deadline responseDeadline,
                   s.resolution_deadline resolutionDeadline,s.response_status responseStatus,
                   s.resolution_status resolutionStatus,s.escalation_level escalationLevel,
                   s.create_time createTime,p.warning_percent warningPercent,
                   p.response_minutes responseMinutes,p.resolution_minutes resolutionMinutes
            FROM ticket_sla s JOIN sla_policy p ON p.id=s.policy_id
            WHERE s.next_check_time IS NOT NULL AND s.next_check_time<=NOW()
              AND s.resolution_status IN ('RUNNING','BREACHED')
            ORDER BY s.next_check_time LIMIT #{limit}
            """)
    List<SlaCandidate> due(int limit);

    @Insert(
            """
            INSERT IGNORE INTO ticket_sla_event(
                sla_id,ticket_id,event_type,escalation_level,detail,create_time)
            VALUES(#{slaId},#{ticketId},#{eventType},#{level},#{detail},NOW())
            """)
    int addEvent(long slaId, long ticketId, String eventType, int level, String detail);

    @Update(
            """
            UPDATE ticket_sla SET
                response_status=IF(#{eventType}='RESPONSE_BREACH','BREACHED',response_status),
                resolution_status=IF(#{eventType}='RESOLUTION_BREACH','BREACHED',resolution_status),
                escalation_level=GREATEST(escalation_level,#{level}),
                next_check_time=DATE_ADD(NOW(),INTERVAL 10 SECOND),
                version=version+1,update_time=NOW()
            WHERE id=#{slaId}
            """)
    int markEvent(long slaId, String eventType, int level);

    @Select(
            """
            SELECT s.id,s.ticket_id ticketId,p.policy_name policyName,p.priority,
                   s.response_deadline responseDeadline,s.resolution_deadline resolutionDeadline,
                   s.response_status responseStatus,s.resolution_status resolutionStatus,
                   s.response_time responseTime,s.resolution_time resolutionTime,
                   s.escalation_level escalationLevel,s.update_time updateTime
            FROM ticket_sla s JOIN sla_policy p ON p.id=s.policy_id
            WHERE s.ticket_id=#{ticketId}
            """)
    Map<String, Object> detail(long ticketId);

    @Select(
            """
            SELECT s.id,s.ticket_id ticketId,t.ticket_no ticketNo,t.title,t.priority,t.status,
                   t.affected_ci_code affectedCiCode,s.response_deadline responseDeadline,
                   s.resolution_deadline resolutionDeadline,s.response_status responseStatus,
                   s.resolution_status resolutionStatus,s.escalation_level escalationLevel
            FROM ticket_sla s JOIN ticket t ON t.id=s.ticket_id
            WHERE t.deleted=0 ORDER BY
                FIELD(s.resolution_status,'BREACHED','RUNNING','COMPLETED'),
                s.resolution_deadline LIMIT #{limit}
            """)
    List<Map<String, Object>> overview(int limit);

    @Select("<script>SELECT COUNT(*) " + PAGE_FILTER + "</script>")
    long countPage(
            @Param("query") SlaDtos.Query query,
            @Param("now") LocalDateTime now,
            @Param("riskUntil") LocalDateTime riskUntil);

    @Select(
            "<script>"
                    + """
                    SELECT s.id,s.ticket_id ticketId,t.ticket_no ticketNo,t.title,t.priority,t.status,
                           t.affected_ci_code affectedCiCode,s.response_deadline responseDeadline,
                           s.resolution_deadline resolutionDeadline,s.response_status responseStatus,
                           s.resolution_status resolutionStatus,s.escalation_level escalationLevel
                    """
                    + PAGE_FILTER
                    + """
                    ORDER BY CASE s.resolution_status WHEN 'BREACHED' THEN 0 WHEN 'RUNNING' THEN 1 ELSE 2 END,
                             s.resolution_deadline,s.id
                    LIMIT #{query.pageSize} OFFSET #{offset}
                    </script>
                    """)
    List<SlaDtos.Row> page(
            @Param("query") SlaDtos.Query query,
            @Param("now") LocalDateTime now,
            @Param("riskUntil") LocalDateTime riskUntil,
            @Param("offset") long offset);

    @Select(
            """
            SELECT COUNT(*) total,
                   COALESCE(SUM(CASE WHEN s.resolution_status='RUNNING' THEN 1 ELSE 0 END),0) running,
                   COALESCE(SUM(CASE WHEN s.resolution_status='RUNNING'
                     AND s.resolution_deadline > #{now} AND s.resolution_deadline <= #{riskUntil}
                     THEN 1 ELSE 0 END),0) risk,
                   COALESCE(SUM(CASE WHEN s.resolution_status='RUNNING'
                     AND s.resolution_deadline > '1970-01-01 00:00:00'
                     AND s.resolution_deadline <= #{riskUntil} THEN 1 ELSE 0 END),0) dashboardRisk,
                   COALESCE(SUM(CASE WHEN s.resolution_status='BREACHED' THEN 1 ELSE 0 END),0) breached,
                   COALESCE(SUM(CASE WHEN s.resolution_status='COMPLETED' THEN 1 ELSE 0 END),0) completed
            FROM ticket_sla s JOIN ticket t ON t.id=s.ticket_id WHERE t.deleted=0
            """)
    SlaDtos.Counts counts(@Param("now") LocalDateTime now, @Param("riskUntil") LocalDateTime riskUntil);

    @Select(
            """
            SELECT DISTINCT t.affected_ci_code
            FROM ticket_sla s JOIN ticket t ON t.id=s.ticket_id
            WHERE t.deleted=0 AND t.affected_ci_code IS NOT NULL AND t.affected_ci_code<>''
            ORDER BY t.affected_ci_code
            """)
    List<String> services();

    /**
     * SLA 扫描所需的不可变数据快照。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record SlaCandidate(
            long id,
            long ticketId,
            LocalDateTime responseDeadline,
            LocalDateTime resolutionDeadline,
            String responseStatus,
            String resolutionStatus,
            int escalationLevel,
            LocalDateTime createTime,
            int warningPercent,
            int responseMinutes,
            int resolutionMinutes) {}
}
