package com.example.opsagent.ticket.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.ticket.dao.TicketStatusLogDao;
import com.example.opsagent.ticket.entity.TicketStatusLog;
import com.example.opsagent.ticket.service.TicketStatusLogService;

import org.springframework.stereotype.Service;

/**
 * 工单状态日志业务服务实现。
 *
 * @author heyu
 * @since 2026/7/16
 */
@Service
public class TicketStatusLogServiceImpl extends ServiceImpl<TicketStatusLogDao, TicketStatusLog>
        implements TicketStatusLogService {}
