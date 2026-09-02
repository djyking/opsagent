package com.opsagent.common.mq;

import java.time.Instant;

/** 跨服务传递的领域事件信封，业务数据由泛型载荷承载。 */
public record DomainEvent<T>(String eventId, String eventType, Instant occurredAt, T payload) {}
