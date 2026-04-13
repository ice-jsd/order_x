-- 抢购执行器 Redis 协调化改造

ALTER TABLE `ticket_order_execution`
  ADD COLUMN `worker_id` varchar(128) DEFAULT NULL COMMENT '执行节点标识' AFTER `raw_result`,
  ADD COLUMN `attempt_count` int NOT NULL DEFAULT 0 COMMENT '执行尝试次数' AFTER `worker_id`,
  ADD COLUMN `heartbeat_at` datetime DEFAULT NULL COMMENT '最近心跳时间' AFTER `attempt_count`,
  ADD COLUMN `started_at` datetime DEFAULT NULL COMMENT '开始执行时间' AFTER `heartbeat_at`;

UPDATE `ticket_order_execution`
SET `attempt_count` = 0
WHERE `attempt_count` IS NULL;
