package com.opsagent.ticket;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 持久化 Alertmanager 告警快照和每次 webhook 事件。
 *
 * @author heyu
 * @since 2026/9/3
 */
public interface AlertMapper {
    @Insert(
            """
INSERT IGNORE INTO monitor_alert(
    fingerprint,alert_name,severity,service_code,current_status,
    occurrence_count,first_seen_time,last_seen_time,resolved_time,
    labels_json,annotations_json)
VALUES(#{fingerprint},#{alertName},#{severity},#{serviceCode},#{status},
       1,#{seenTime},#{seenTime},
       CASE WHEN #{status}='resolved' THEN #{seenTime} ELSE NULL END,#{labels},#{annotations})
""")
    int insert(
            String fingerprint,
            String alertName,
            String severity,
            String serviceCode,
            String status,
            LocalDateTime seenTime,
            String labels,
            String annotations);

    @Select(
            """
            SELECT id,fingerprint,ticket_id ticketId,alert_name alertName,severity,
                   service_code serviceCode,current_status currentStatus,
                   occurrence_count occurrenceCount,first_seen_time firstSeenTime,
                   last_seen_time lastSeenTime,resolved_time resolvedTime
            FROM monitor_alert WHERE fingerprint=#{fingerprint} FOR UPDATE
            """)
    AlertRecord lockByFingerprint(String fingerprint);

    @Update(
            """
            UPDATE monitor_alert SET ticket_id=#{ticketId},last_seen_time=#{seenTime},
                current_status='firing',resolved_time=NULL,
                labels_json=#{labels},annotations_json=#{annotations}
            WHERE id=#{id}
            """)
    int linkTicket(
            long id, long ticketId, LocalDateTime seenTime, String labels, String annotations);

    @Update("UPDATE monitor_alert SET service_code=#{targetCode} WHERE id=#{id}")
    int bindService(long id, String targetCode);

    @Update(
            """
            UPDATE monitor_alert SET current_status='firing',
                resolved_time=NULL,
                occurrence_count=occurrence_count+1,last_seen_time=#{seenTime},
                severity=#{severity},service_code=#{serviceCode},
                labels_json=#{labels},annotations_json=#{annotations}
            WHERE id=#{id}
            """)
    int duplicateFiring(
            long id,
            LocalDateTime seenTime,
            String severity,
            String serviceCode,
            String labels,
            String annotations);

    @Update(
            """
            UPDATE monitor_alert SET current_status='resolved',last_seen_time=#{seenTime},
                resolved_time=#{seenTime},labels_json=#{labels},annotations_json=#{annotations}
            WHERE id=#{id}
            """)
    int resolved(long id, LocalDateTime seenTime, String labels, String annotations);

    @Insert(
            """
            INSERT INTO monitor_alert_event(alert_id,event_status,payload_json,create_time)
            VALUES(#{alertId},#{status},#{payload},NOW())
            """)
    int event(long alertId, String status, String payload);

    @Select(
            """
            SELECT a.id,a.fingerprint,a.ticket_id ticketId,a.alert_name alertName,a.severity,
                   a.service_code serviceCode,a.current_status currentStatus,
                   a.occurrence_count occurrenceCount,a.first_seen_time firstSeenTime,
                   a.last_seen_time lastSeenTime,a.resolved_time resolvedTime,
                   t.ticket_no ticketNo,t.status ticketStatus
            FROM monitor_alert a LEFT JOIN ticket t ON t.id=a.ticket_id AND t.deleted=0
            WHERE (#{status}='' OR a.current_status=#{status})
            ORDER BY a.last_seen_time DESC LIMIT 200
            """)
    List<Map<String, Object>> list(String status);

    @Select(
            """
            SELECT a.id,a.fingerprint,a.ticket_id ticketId,a.alert_name alertName,a.severity,
                   a.service_code serviceCode,a.current_status currentStatus,
                   a.occurrence_count occurrenceCount,a.first_seen_time firstSeenTime,
                   a.last_seen_time lastSeenTime,a.resolved_time resolvedTime,
                   t.ticket_no ticketNo,t.status ticketStatus
            FROM monitor_alert a JOIN ticket t ON t.id=a.ticket_id AND t.deleted=0
            WHERE (#{status}='' OR a.current_status=#{status})
              AND (t.owner_actor_id=#{actorId} OR t.public_demo=1)
            ORDER BY a.last_seen_time DESC LIMIT 200
            """)
    List<Map<String, Object>> listVisible(String status, long actorId);

    /**
     * 加锁后的告警聚合快照。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record AlertRecord(
            long id,
            String fingerprint,
            Long ticketId,
            String alertName,
            String severity,
            String serviceCode,
            String currentStatus,
            int occurrenceCount,
            LocalDateTime firstSeenTime,
            LocalDateTime lastSeenTime,
            LocalDateTime resolvedTime) {}
}
