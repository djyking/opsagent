package com.opsagent.ticket;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

/**
 * 告警每次实际故障独立分期，保留恢复事件和重复投递的幂等边界。
 *
 * @author heyu
 * @since 2026/9/3
 */
public interface AlertEpisodeMapper {
    @Insert(
            """
            INSERT IGNORE INTO monitor_alert_episode(
              episode_id,alert_id,fingerprint,starts_at,last_event_time,current_status)
            VALUES(#{episodeId},#{alertId},#{fingerprint},#{startsAt},#{startsAt},'pending')
            """)
    int insert(String episodeId, long alertId, String fingerprint, LocalDateTime startsAt);

    @Select(
            """
            SELECT episode_id episodeId,alert_id alertId,fingerprint,starts_at startsAt,
              last_event_time lastEventTime,current_status currentStatus,ticket_id ticketId,
              incident_id incidentId,owner_actor_id ownerActorId,environment,resolved_at resolvedAt
            FROM monitor_alert_episode WHERE episode_id=#{id} FOR UPDATE
            """)
    Episode lock(String id);

    @Select("SELECT MAX(starts_at) FROM monitor_alert_episode WHERE alert_id=#{alertId}")
    LocalDateTime latestStart(long alertId);

    @Insert(
            """
            INSERT IGNORE INTO monitor_alert_delivery(delivery_key,episode_id,event_status,event_time)
            VALUES(#{key},#{episodeId},#{status},#{time})
            """)
    int delivery(String key, String episodeId, String status, LocalDateTime time);

    @Update(
            """
            UPDATE monitor_alert_episode SET current_status=#{status},last_event_time=#{time},
              resolved_at=CASE WHEN #{status}='resolved' THEN #{time} ELSE NULL END
            WHERE episode_id=#{id}
            """)
    int observed(String id, String status, LocalDateTime time);

    @Update(
            """
            UPDATE monitor_alert_episode SET ticket_id=#{ticketId},incident_id=#{incidentId},
              owner_actor_id=#{ownerActorId},environment=#{environment}
            WHERE episode_id=#{id} AND ticket_id IS NULL
            """)
    int link(String id, long ticketId, String incidentId, long ownerActorId, String environment);

    record Episode(
            String episodeId,
            long alertId,
            String fingerprint,
            LocalDateTime startsAt,
            LocalDateTime lastEventTime,
            String currentStatus,
            Long ticketId,
            String incidentId,
            long ownerActorId,
            String environment,
            LocalDateTime resolvedAt) {}
}
