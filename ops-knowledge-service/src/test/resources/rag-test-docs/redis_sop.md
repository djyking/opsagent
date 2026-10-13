# Redis 生产故障手册

## 缓存命中率下降

当命中率连续五分钟低于 80% 时，先确认是否存在 TTL 集中失效。

### 排查步骤

1. 执行 `redis-cli --latency` 检查延迟。
2. 查询 OPS-SCENE-1007 对应的变更记录。
3. 不要删除错误码、端口和命令行参数。

```bash
redis-cli -h 127.0.0.1 -p 6379 info stats
redis-cli -h 127.0.0.1 -p 6379 slowlog get 10
```

| 指标 | 告警阈值 | 处理动作 |
| --- | --- | --- |
| hit_rate | 80% | 检查热键 |
| evicted_keys | 100/s | 检查内存策略 |
