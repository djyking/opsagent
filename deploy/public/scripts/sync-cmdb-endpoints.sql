-- Update only legacy localhost registrations. Preserve user-managed nonlocal endpoints.
UPDATE cmdb_ci
SET endpoint = CASE ci_code
    WHEN 'ops-gateway' THEN 'https://opsagent.cloud'
    WHEN 'ops-auth-service' THEN 'http://ops-auth-app:8101'
    WHEN 'ops-ticket-service' THEN 'http://ops-ticket-app:8102'
    WHEN 'ops-knowledge-service' THEN 'http://ops-knowledge-app:8103'
    WHEN 'ops-rag-service' THEN 'http://ops-rag-app:8104'
    WHEN 'ops-platform-service' THEN 'http://ops-platform-app:8105'
    WHEN 'prometheus' THEN 'http://prometheus:9090'
    WHEN 'grafana' THEN 'http://grafana:3000'
    WHEN 'alertmanager' THEN 'http://alertmanager:9093'
    WHEN 'nacos' THEN 'http://nacos:8848/nacos'
    WHEN 'sentinel' THEN 'http://sentinel:8858'
    ELSE endpoint END
WHERE status='ACTIVE' AND (endpoint LIKE 'http://127.0.0.1:%' OR endpoint LIKE 'http://localhost:%')
  AND ci_code IN ('ops-gateway','ops-auth-service','ops-ticket-service','ops-knowledge-service',
      'ops-rag-service','ops-platform-service','prometheus','grafana','alertmanager','nacos','sentinel');
