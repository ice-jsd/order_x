-- LivePocket 抽票分时段执行计划

CREATE TABLE IF NOT EXISTS `ticket_sale_task_schedule` (
  `schedule_id` bigint NOT NULL COMMENT '抽票时段ID',
  `task_id` bigint NOT NULL COMMENT '抢购任务ID',
  `scheduled_time` datetime NOT NULL COMMENT '执行时间',
  `session_id` varchar(100) DEFAULT NULL COMMENT '抽票场次ID',
  `session_label` varchar(200) DEFAULT NULL COMMENT '抽票场次名称',
  `account_count` int NOT NULL DEFAULT 0 COMMENT '本时段账号数量',
  `schedule_status` varchar(32) NOT NULL DEFAULT 'pending' COMMENT '时段状态 pending/running/completed/partial/failed',
  `dispatched_time` datetime DEFAULT NULL COMMENT '分发时间',
  `finished_time` datetime DEFAULT NULL COMMENT '完成时间',
  `result_message` varchar(500) DEFAULT NULL COMMENT '结果信息',
  `del_flag` bigint NOT NULL DEFAULT 0 COMMENT '删除标志',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`schedule_id`),
  KEY `idx_ticket_sale_task_schedule_task` (`task_id`),
  KEY `idx_ticket_sale_task_schedule_due` (`schedule_status`, `scheduled_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='票务抽票任务分时段计划表';

SET @schema_name = DATABASE();

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `ticket_order_execution` ADD COLUMN `lottery_schedule_id` bigint DEFAULT NULL COMMENT ''抽票时段ID'' AFTER `task_id`',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'ticket_order_execution'
    AND COLUMN_NAME = 'lottery_schedule_id'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'CREATE INDEX `idx_ticket_order_execution_lottery_schedule` ON `ticket_order_execution` (`lottery_schedule_id`)',
    'SELECT 1'
  )
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'ticket_order_execution'
    AND INDEX_NAME = 'idx_ticket_order_execution_lottery_schedule'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
