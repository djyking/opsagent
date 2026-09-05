# OpsAgent 公网部署目录

目标：Ubuntu 24.04 x86_64、6 CPU、12 GB 内存；正式入口 `https://opsagent.cloud`，`www` 和 HTTP 跳转到根域 HTTPS。该目录独立运行，不叠加仓库根目录 Compose，也不使用开发服务器 5173。

## 运行结构

- 默认启动完整的 18 个服务。仅 `ops-web-app` 发布主机 80/443；Java、MySQL、Redis、RabbitMQ、Nacos、ES、Qdrant、reranker、Prometheus、Alertmanager、Grafana均使用 Docker DNS 和私有网桥。
- 网桥允许应用访问已经授权的外部 AI API。数据库等没有主机端口，不需要打开安全组入口。
- 复用 `opsagent/web:1.0.0` 内置的 Nginx 和前端产物。没有第二层 Nginx，没有 Caddy。Certbot只在申请/续期时运行。
- 保留低内存 JVM、Nacos和中间件预算。reranker保留F32模型、512长度、batch2、并发1，使用4 CPU及3 GiB上限。ES堆保持512 MiB。
- 容器上限不等于预留内存；各容器上限相加可以超过宿主容量。上线后应观察宿主可用内存、原始cgroup及查询峰值，不能只看扣除文件缓存的Docker工作集。
- 注册关闭；AI全局并发2、每分钟10次、UTC每日100次，配合后端持久化预算实现。向量/embedding开关必须显式继承已批准设置，当前模板为false，不能因部署自动开启付费embedding。

## 上线前必须准备

1. 把本目录放到服务器，例如 `/opt/opsagent/deploy/public`；后续命令均从该目录执行。
2. 准备已有构建镜像，尤其六个Java镜像、web、带smartcn的ES及reranker。Compose只引用镜像，不在12GB运行服务器上并发构建所有Java服务。
3. 复制 `.env.example` 为私有 `secret.env`，填入独立公网密码/JWT/Nacos令牌和已经授权的AI密钥。执行 `chmod 600 secret.env`。文件被Git忽略，不得提交。空的required变量会使Compose立即报错。
4. 将Sentinel JAR放到 `runtime/sentinel-dashboard-1.8.9.jar`；它被Git忽略，需随运行包或单独传输。
5. 将独立告警令牌写到 `secrets/alertmanager-webhook-token.txt`，内容必须与 `secret.env` 的 `OPS_ALERTMANAGER_WEBHOOK_TOKEN` 一致。此文件不会随源码提交。Alertmanager使用非root用户，文件需可被容器进程读取；可设置文件0444、宿主secrets目录0700。
6. 数据库导入后建立专用 `opsagent_app` 账号，仅授权 `ops_auth`、`ops_ticket`、`ops_knowledge`、`ops_rag`、`ops_platform` 五个数据库；密码为 `OPS_DB_APP_PASSWORD`。应用不会使用root，root仅供迁移/备份/管理。根据迁移脚本的schema初始化需求授予库级权限，不给全局管理员权限。
7. RabbitMQ节点hostname必须匹配原Mnesia目录。此次源值为 `026053d50e11`，模板已填入；直接改成新hostname会使已迁移数据看似丢失。导入旧volume后，`RABBITMQ_DEFAULT_PASS`不会自动改变已有账号密码，需要管理命令另行轮换并验证。
8. 同样，恢复旧MySQL/Grafana数据后，环境变量不会自动改写已经存在的密码；必须通过对应管理命令完成独立公网密码轮换。
9. 配置宿主 `vm.max_map_count=262144` 以满足ES；主机防火墙只开放SSH和80/443，具体SSH限制由部署人员管理。

配置检查（不启动服务，也不要把完整解析结果打印到共享日志）：

```sh
docker compose --env-file secret.env -f compose.yaml config --quiet
```

## 数据与模型迁移

项目名固定为 `opsagent`，现有业务volume映射保持如下：

| 内容 | Docker volume |
| --- | --- |
| MySQL | `opsagent_mysql-data` |
| Redis | `opsagent_redis-data` |
| RabbitMQ | `opsagent_rabbitmq-data` |
| Nacos持久化配置 | `opsagent_nacos-data` |
| Elasticsearch | `opsagent_elasticsearch-data` |
| Qdrant | `opsagent_qdrant-data` |
| 知识库附件 | `opsagent_knowledge-uploads` |
| reranker模型缓存 | `opsagent_reranker-models` |
| Prometheus | `opsagent_prometheus-data` |
| Alertmanager | `opsagent_alertmanager-data` |
| Grafana | `opsagent_grafana-data` |

在云端运行服务前恢复数据，保留原数据库、文件属主和RabbitMQ节点身份。不要复制正在写入的数据库目录；使用一致性导出/快照或相应停写窗口。不要删除本地数据或任何已有named volume。新JWT与新会话密钥应独立设置，旧认证会话不应作为公网登录凭据继续使用。

Nacos原配置中若含本地地址、密钥或Windows路径，应在迁移副本中核对并改为Docker服务名；不要让持久化配置反向覆盖公网环境变量。监控文件已迁移到本目录并改为Docker DNS，无需依赖原Windows路径。

reranker固定使用 `BAAI/bge-reranker-v2-m3`，已验证的模型revision为 `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`。可在云端把同一revision下载到模型volume并校验权重SHA256。服务启用了offline模式，因此必须完整保留tokenizer/config/权重及Hugging Face cache相对符号链接，并让 `hub/models--BAAI--bge-reranker-v2-m3/refs/main` 指向该revision；仅下载一个snapshot目录而不建立main引用可能导致离线加载失败。

## HTTP引导与HTTPS启用

确保DNS only状态下根域和 `www` 都指向服务器，且没有指向其他服务器的残留AAAA记录。主代理负责DNS核对和证书申请；本目录不操作任何云/DNS账户。

准备HTTP引导配置并启动完整系统：

```sh
cp config/nginx/bootstrap.conf config/nginx/active.conf
docker compose --env-file secret.env -f compose.yaml up -d
docker compose --env-file secret.env -f compose.yaml ps
```

引导阶段用于验证前端、匿名API和ACME路径。确认HTTP连通后立即申请证书；账号登录和业务数据验收在HTTPS启用后进行。

使用真实管理员邮箱申请同时覆盖根域和www的证书：

```sh
docker compose --env-file secret.env -f compose.yaml --profile tls run --rm certbot certonly \
  --webroot -w /var/www/certbot --cert-name opsagent.cloud \
  -d opsagent.cloud -d www.opsagent.cloud --email YOUR_ADMIN_EMAIL --agree-tos --non-interactive
```

成功后切换到HTTPS配置。`active.conf`是单文件bind mount，必须覆盖原文件内容，不能用临时文件rename替换inode，否则容器可能仍读旧配置。

```sh
cat config/nginx/https.conf > config/nginx/active.conf
docker compose --env-file secret.env -f compose.yaml exec -T ops-web-app nginx -t
docker compose --env-file secret.env -f compose.yaml exec -T ops-web-app nginx -s reload
```

若检查失败，先查看具体错误，不要盲目reload；需要恢复引导时同样用 `cat bootstrap.conf > active.conf` 覆盖内容再检查和reload。

证书和ACME文件分别位于 `opsagent_letsencrypt`、`opsagent_acme-webroot`。安排宿主定时执行续期命令，并在成功后重新加载同一个web容器：

```sh
docker compose --env-file secret.env -f compose.yaml --profile tls run --rm certbot renew \
  --webroot -w /var/www/certbot --quiet
docker compose --env-file secret.env -f compose.yaml exec -T ops-web-app nginx -t
docker compose --env-file secret.env -f compose.yaml exec -T ops-web-app nginx -s reload
```

正式启用后先运行一次 `certbot renew --dry-run` 验证续期链路，之后设置每日检查。不要删除已有证书volume。

## 验收与运维

- HTTP根域、HTTP www、HTTPS www应跳转到 `https://opsagent.cloud`，路径与查询串保持不变。
- HTTPS前端深链接应返回SPA入口；不存在的版本化 `/assets/` 文件应返回404，不能伪装成HTML。
- API响应禁止缓存，SSE关闭响应与请求缓冲，读写超时900秒。
- `/actuator`、文档接口、公开注册、知识库私有Feign接口、Alertmanager内部回调在公网Nginx均返回404；容器内部接口仍可正常访问。
- Prometheus应有六个业务抓取目标正常；Grafana数据源使用 `http://prometheus:9090`。监控站点不公开，也不输出localhost外链。
- 通过专用演示账号验收验证码、登录、工单、附件、知识检索、AI问答、流式完成、rerank繁忙回退、注册关闭和每日预算。不要把账号密码或API令牌写入验收截图和报告。
- 监控管理通过SSH及 `docker compose exec` 完成。若确需浏览器管理界面，使用单次SSH隧道指向对应容器IP，不应临时把管理端口发布到公网。
- 保存镜像、源代码、MySQL逻辑备份、附件和持久化数据的可恢复备份。更新时避免 `down -v` 等删除数据操作。

仓库中的 `.env.example`、配置文件和说明可提交；私有env、告警令牌、JAR、active.conf、数据库、镜像包及证书不提交Git。

## 每日轻量备份与自动续期

`scripts/backup-daily.sh` 固定从 `/opt/opsagent/deploy/public/secret.env` 读取Compose配置。它检查18个服务的现有Docker健康状态（没有healthcheck的服务检查running），在线导出5个业务库的单事务SQL，并归档知识库附件。不会停站，不会每天复制模型、ES、Qdrant或整套监控数据。SQL和附件压缩文件通过校验后才标记成功；命令失败或前后健康检查失败均以非零状态退出。

备份保存在 `/opt/opsagent/backups/opsagent-backup-UTC时间-随机后缀/`，默认权限仅root可读。每次成功后清理超过7天的成功备份。删除前同时检查固定根目录、真实绝对路径、直接子目录、命名格式、非符号链接和本脚本的所有权/完成标记；其他文件、目录和volume不会被删除。压缩失败的当前备份仅在通过同样路径验证后清理。

这是在线恢复点：MySQL自身的SQL快照一致，但文件系统与SQL不构成一个全局事务。若备份期间有上传，恢复时需要核对最近上传的附件；tar检测到归档期间文件变化会让本次备份失败。正式服务器迁移仍应使用已执行过的停写完整导出方案。

`scripts/renew-certificates.sh` 使用相同私有env、现有Certbot服务及证书volume，成功后先测试Nginx配置再reload；支持 `--dry-run`，该模式不reload，也不改变正式证书。它不会启动第二层代理。

在服务器首次安装时，先手动验证，再启用定时器（这些命令需要由部署人员实际执行）：

```sh
cd /opt/opsagent/deploy/public
bash scripts/backup-daily.sh
bash scripts/renew-certificates.sh --dry-run
install -m 0644 scripts/systemd/opsagent-*.service scripts/systemd/opsagent-*.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now opsagent-backup.timer opsagent-cert-renew.timer
systemctl list-timers 'opsagent-*'
```

示例调度使用UTC：每日02:30加最多15分钟随机延迟进行备份；每日00:15与12:15加最多1小时随机延迟检查证书续期。`Persistent=true`会补做关机时错过的任务。需调整时间时修改timer中的 `OnCalendar`，之后reload并重启对应timer。

查看结果与失败原因：

```sh
systemctl status opsagent-backup.service opsagent-cert-renew.service
journalctl -u opsagent-backup.service -u opsagent-cert-renew.service --since '2 days ago'
```

本机同盘备份可用于误操作恢复，不防服务器磁盘损坏；如需故障恢复，应另行保存加密异地副本，不把这些包含业务数据的文件放到网站目录或Git。

## 从每日备份恢复

恢复会覆盖同名业务表，应先选择维护窗口并保存恢复前备份。以下为人工恢复步骤，定时脚本不会执行恢复或删除数据volume。

1. 选择一个包含 `.complete` 的成功备份，在该目录执行 `sha256sum --check SHA256SUMS`；先验证全部文件，再进行数据库操作。
2. 暂停备份timer，并停止六个业务服务，防止恢复过程中继续写入；MySQL保持运行。
3. 从SQL恢复五个业务库，并把附件归档恢复到原知识库volume。附件恢复覆盖同名文件，不清空volume。
4. 启动原有服务，核验账号、工单、知识库及附件，再恢复定时备份。

```sh
cd /opt/opsagent/deploy/public
# Replace this with an existing verified backup directory.
set -Eeuo pipefail
BACKUP=/opt/opsagent/backups/opsagent-backup-YYYYMMDDTHHMMSSZ-XXXXXXXX
test -f "$BACKUP/.complete"
(cd "$BACKUP" && sha256sum --check SHA256SUMS)
systemctl stop opsagent-backup.timer
systemctl stop opsagent-backup.service
docker compose --env-file secret.env -f compose.yaml stop \
  ops-auth-app ops-ticket-app ops-knowledge-app ops-rag-app ops-platform-app ops-gateway-app
gzip -dc "$BACKUP/mysql.sql.gz" | docker compose --env-file secret.env -f compose.yaml exec -T mysql \
  sh -c 'exec env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4'
docker compose --env-file secret.env -f compose.yaml run --rm --no-deps --user 0:0 --entrypoint tar \
  --volume "$BACKUP/knowledge-uploads.tgz:/restore/knowledge-uploads.tgz:ro" ops-knowledge-app \
  --numeric-owner -xzf /restore/knowledge-uploads.tgz -C /app/data/uploads
docker compose --env-file secret.env -f compose.yaml start --wait --wait-timeout 240
# After business and attachment checks pass:
systemctl start opsagent-backup.timer
```

保留校验文件、恢复时间和验收结果。任何步骤失败时先查看错误，不继续执行后续数据库覆盖或自动重试恢复。
