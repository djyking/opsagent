USE ops_auth;
INSERT INTO sys_role(id,code,name,status,deleted) VALUES(1,'ADMIN','管理员','enable',0),(2,'OPS','运维人员','enable',0),(3,'USER','普通用户','enable',0) ON DUPLICATE KEY UPDATE name=VALUES(name),status=VALUES(status),deleted=0;
INSERT INTO sys_permission(id,code,name,type,path,method,status) VALUES(1,'ticket:read','查看工单','API','/api/tickets/**','GET','enable'),(2,'ticket:operate','处理工单','API','/api/tickets/**','POST','enable'),(3,'knowledge:manage','知识库管理','API','/api/knowledge/**','ALL','enable'),(4,'platform:admin','平台管理','API','/api/platform/**','ALL','enable') ON DUPLICATE KEY UPDATE name=VALUES(name),status='enable';
INSERT INTO sys_user(id,username,password,display_name,status,deleted) VALUES
(1,'admin','$2a$10$2K0YuJta8zWfXR4SQPX8LubohC4pnFk1Smn4XEof4xeHdMXghsV5m','系统管理员','enable',0),
(2,'ops','$2a$10$TpKqdWswnwVvFz/nkUvvaebCc8e6zoEPqV32eh6fec6YUFLoXx9hi','运维人员','enable',0),
(3,'user','$2a$10$TX57ZL7yUst.7h06IHgavu7SgpcQzIrYgFYG0lbjJU8vJZQE5aNPC','普通用户','enable',0)
ON DUPLICATE KEY UPDATE password=VALUES(password),display_name=VALUES(display_name),status='enable',deleted=0;
INSERT IGNORE INTO sys_user_role(user_id,role_id) VALUES(1,1),(2,2),(3,3);
INSERT IGNORE INTO sys_role_permission(role_id,permission_id) VALUES(1,1),(1,2),(1,3),(1,4),(2,1),(2,2),(2,3),(3,1);
USE ops_knowledge;
INSERT INTO knowledge_base(id,name,description,status,create_by,deleted) VALUES(1,'默认运维知识库','用于本地演示和运维文档检索','enable',1,0) ON DUPLICATE KEY UPDATE description=VALUES(description),status='enable',deleted=0;
USE ops_ticket;
INSERT INTO ticket(id,ticket_no,title,description,priority,status,creator_id,version,deleted) VALUES(1,'OPS-DEMO-0001','线上数据库连接超时','应用连接 MySQL 时持续超时，请排查连接池与数据库负载。','HIGH','CREATED',3,0,0) ON DUPLICATE KEY UPDATE title=VALUES(title),description=VALUES(description),priority=VALUES(priority),deleted=0;
