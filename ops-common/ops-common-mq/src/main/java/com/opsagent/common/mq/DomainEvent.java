package com.opsagent.common.mq;
import java.time.Instant;
public record DomainEvent<T>(String eventId,String eventType,Instant occurredAt,T payload){}
