package com.opsagent.ticket;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.*;

/**
 * 工单数据访问接口，包含带状态和版本条件的原子更新。
 *
 * @author heyu
 * @since 2026/8/11
 */
public interface TicketMapper extends BaseMapper<Ticket> {
    @Update(
            "UPDATE ticket SET"
                    + " assignee_id=#{userId},status='ASSIGNED',version=version+1,update_time=NOW()"
                    + " WHERE id=#{id} AND status='CREATED' AND version=#{version} AND deleted=0")
    int claim(long id, long userId, int version);

    @Update(
            "UPDATE ticket SET status=#{target},version=version+1,update_time=NOW() WHERE id=#{id}"
                    + " AND status=#{source} AND version=#{version} AND deleted=0")
    int transition(long id, String source, String target, int version);
}
