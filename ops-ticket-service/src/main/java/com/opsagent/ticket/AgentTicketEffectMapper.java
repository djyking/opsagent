package com.opsagent.ticket;

import org.apache.ibatis.annotations.*;

/**
 * 与工单副作用同事务持久化的工具幂等账本。
 *
 * @author heyu
 * @since 2026/9/3
 */
public interface AgentTicketEffectMapper {
    @Insert(
            """
            INSERT IGNORE INTO agent_ticket_effect(
              idempotency_key,ticket_id,actor_id,run_id,tool_call_id,operation,request_hash)
            VALUES(#{key},#{ticketId},#{actorId},#{runId},#{toolCallId},#{operation},#{hash})
            """)
    int begin(
            String key,
            long ticketId,
            long actorId,
            String runId,
            String toolCallId,
            String operation,
            String hash);

    @Select(
            "SELECT request_hash requestHash,result_json resultJson FROM agent_ticket_effect WHERE"
                    + " idempotency_key=#{key} FOR UPDATE")
    Effect lock(String key);

    @Update("UPDATE agent_ticket_effect SET result_json=#{result} WHERE idempotency_key=#{key}")
    int complete(String key, String result);

    record Effect(String requestHash, String resultJson) {}
}
