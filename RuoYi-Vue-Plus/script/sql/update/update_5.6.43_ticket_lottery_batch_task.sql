SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `ticket_lottery_batch_task` (
  `batch_task_id` bigint(20) NOT NULL COMMENT '批量抽票任务ID',
  `platform_id` bigint(20) NOT NULL COMMENT '平台ID',
  `task_name` varchar(255) NOT NULL COMMENT '任务名称',
  `task_status` varchar(32) NOT NULL DEFAULT 'draft' COMMENT '任务状态',
  `source_url` varchar(500) NOT NULL COMMENT '活动集合链接',
  `schedule_version` bigint(20) NOT NULL DEFAULT 1 COMMENT '调度版本',
  `task_options` json DEFAULT NULL COMMENT '扩展配置',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`batch_task_id`),
  KEY `idx_ticket_lottery_batch_task_platform_status` (`platform_id`, `task_status`),
  KEY `idx_ticket_lottery_batch_task_tenant_status` (`tenant_id`, `task_status`, `batch_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量抽票任务表';

CREATE TABLE IF NOT EXISTS `ticket_lottery_batch_task_account` (
  `binding_id` bigint(20) NOT NULL COMMENT '绑定ID',
  `batch_task_id` bigint(20) NOT NULL COMMENT '批量抽票任务ID',
  `account_id` bigint(20) NOT NULL COMMENT '账号ID',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`binding_id`),
  UNIQUE KEY `uk_ticket_lottery_batch_task_account` (`batch_task_id`, `account_id`),
  KEY `idx_ticket_lottery_batch_task_account_account` (`account_id`),
  KEY `idx_ticket_lottery_batch_task_account_tenant` (`tenant_id`, `batch_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量抽票任务账号绑定表';

CREATE TABLE IF NOT EXISTS `ticket_lottery_batch_task_item` (
  `batch_item_id` bigint(20) NOT NULL COMMENT '批量抽票活动项ID',
  `batch_task_id` bigint(20) NOT NULL COMMENT '批量抽票任务ID',
  `event_url` varchar(500) NOT NULL COMMENT '活动链接',
  `event_title` varchar(500) DEFAULT NULL COMMENT '活动标题',
  `reception_id` varchar(128) DEFAULT NULL COMMENT '受付ID',
  `reception_title` varchar(255) DEFAULT NULL COMMENT '受付标题',
  `sales_type` varchar(64) DEFAULT NULL COMMENT '销售类型',
  `selected_sessions_json` longtext COMMENT '已选场次JSON',
  `item_status` varchar(32) NOT NULL DEFAULT 'draft' COMMENT '活动项状态',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`batch_item_id`),
  KEY `idx_ticket_lottery_batch_task_item_task` (`batch_task_id`, `item_status`),
  KEY `idx_ticket_lottery_batch_task_item_reception` (`reception_id`),
  KEY `idx_ticket_lottery_batch_task_item_tenant` (`tenant_id`, `batch_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量抽票任务活动项表';

CREATE TABLE IF NOT EXISTS `ticket_lottery_batch_task_item_schedule` (
  `schedule_id` bigint(20) NOT NULL COMMENT '活动项分时段ID',
  `batch_item_id` bigint(20) NOT NULL COMMENT '批量抽票活动项ID',
  `session_id` varchar(128) NOT NULL COMMENT '场次ID',
  `session_label` varchar(500) DEFAULT NULL COMMENT '场次标题',
  `scheduled_time` datetime NOT NULL COMMENT '计划执行时间',
  `account_count` int(11) NOT NULL DEFAULT 0 COMMENT '账号数量',
  `schedule_status` varchar(32) NOT NULL DEFAULT 'pending' COMMENT '分时段状态',
  `dispatched_time` datetime DEFAULT NULL COMMENT '下发时间',
  `finished_time` datetime DEFAULT NULL COMMENT '完成时间',
  `result_message` varchar(500) DEFAULT NULL COMMENT '结果信息',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`schedule_id`),
  KEY `idx_ticket_lottery_batch_task_schedule_item_time` (`batch_item_id`, `scheduled_time`),
  KEY `idx_ticket_lottery_batch_task_schedule_status` (`schedule_status`, `scheduled_time`),
  KEY `idx_ticket_lottery_batch_task_schedule_tenant` (`tenant_id`, `batch_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量抽票任务活动项分时段表';

SET @schema_name := DATABASE();

SELECT COUNT(1) INTO @ticket_order_execution_batch_task_id_exists
FROM information_schema.columns
WHERE table_schema = @schema_name
  AND table_name = 'ticket_order_execution'
  AND column_name = 'batch_task_id';

SET @ticket_order_execution_batch_task_id_sql := IF(
  @ticket_order_execution_batch_task_id_exists = 0,
  'ALTER TABLE `ticket_order_execution` ADD COLUMN `batch_task_id` bigint(20) DEFAULT NULL COMMENT ''批量抽票任务ID'' AFTER `task_id`',
  'SELECT 1'
);
PREPARE stmt_ticket_order_execution_batch_task_id FROM @ticket_order_execution_batch_task_id_sql;
EXECUTE stmt_ticket_order_execution_batch_task_id;
DEALLOCATE PREPARE stmt_ticket_order_execution_batch_task_id;

SELECT COUNT(1) INTO @ticket_order_execution_batch_item_id_exists
FROM information_schema.columns
WHERE table_schema = @schema_name
  AND table_name = 'ticket_order_execution'
  AND column_name = 'batch_item_id';

SET @ticket_order_execution_batch_item_id_sql := IF(
  @ticket_order_execution_batch_item_id_exists = 0,
  'ALTER TABLE `ticket_order_execution` ADD COLUMN `batch_item_id` bigint(20) DEFAULT NULL COMMENT ''批量抽票活动项ID'' AFTER `batch_task_id`',
  'SELECT 1'
);
PREPARE stmt_ticket_order_execution_batch_item_id FROM @ticket_order_execution_batch_item_id_sql;
EXECUTE stmt_ticket_order_execution_batch_item_id;
DEALLOCATE PREPARE stmt_ticket_order_execution_batch_item_id;

SELECT COUNT(*) INTO @idx_ticket_order_execution_batch_status_exists
FROM information_schema.statistics
WHERE table_schema = @schema_name
  AND table_name = 'ticket_order_execution'
  AND index_name = 'idx_ticket_order_execution_batch_status';

SET @idx_ticket_order_execution_batch_status_sql := IF(
  @idx_ticket_order_execution_batch_status_exists = 0,
  'ALTER TABLE `ticket_order_execution` ADD INDEX `idx_ticket_order_execution_batch_status` (`batch_task_id`, `schedule_version`, `execution_status`, `execution_id`)',
  'SELECT 1'
);
PREPARE stmt_idx_ticket_order_execution_batch_status FROM @idx_ticket_order_execution_batch_status_sql;
EXECUTE stmt_idx_ticket_order_execution_batch_status;
DEALLOCATE PREPARE stmt_idx_ticket_order_execution_batch_status;

SELECT COUNT(*) INTO @idx_ticket_order_execution_batch_item_exists
FROM information_schema.statistics
WHERE table_schema = @schema_name
  AND table_name = 'ticket_order_execution'
  AND index_name = 'idx_ticket_order_execution_batch_item';

SET @idx_ticket_order_execution_batch_item_sql := IF(
  @idx_ticket_order_execution_batch_item_exists = 0,
  'ALTER TABLE `ticket_order_execution` ADD INDEX `idx_ticket_order_execution_batch_item` (`batch_task_id`, `batch_item_id`, `account_id`, `lottery_schedule_id`)',
  'SELECT 1'
);
PREPARE stmt_idx_ticket_order_execution_batch_item FROM @idx_ticket_order_execution_batch_item_sql;
EXECUTE stmt_idx_ticket_order_execution_batch_item;
DEALLOCATE PREPARE stmt_idx_ticket_order_execution_batch_item;

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20013, '批量抽票任务', 20000, 11, 'lottery-batch-task', 'ticket/lottery-batch-task/index', '', 1, 0, 'C', '0', '0', 'ticket:lotteryBatchTask:list', 'list', 103, 1, NOW(), NULL, NULL, 'LivePocket 批量抽票任务'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20013)
  AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `path` = 'lottery-batch-task' AND `component` = 'ticket/lottery-batch-task/index');

UPDATE `sys_menu`
SET `menu_name` = '批量抽票任务',
    `parent_id` = 20000,
    `order_num` = 11,
    `path` = 'lottery-batch-task',
    `component` = 'ticket/lottery-batch-task/index',
    `menu_type` = 'C',
    `visible` = '0',
    `status` = '0',
    `perms` = 'ticket:lotteryBatchTask:list',
    `icon` = 'list',
    `remark` = 'LivePocket 批量抽票任务',
    `update_time` = NOW()
WHERE `menu_id` = 20013
   OR (`path` = 'lottery-batch-task' AND `component` = 'ticket/lottery-batch-task/index');

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 21011, '批量抽票查询', 20013, 1, '', '', '', 1, 0, 'F', '0', '0', 'ticket:lotteryBatchTask:query', '#', 103, 1, NOW(), NULL, NULL, '批量抽票任务查询权限'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 21011)
  AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'ticket:lotteryBatchTask:query');

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 21012, '批量抽票新增', 20013, 2, '', '', '', 1, 0, 'F', '0', '0', 'ticket:lotteryBatchTask:add', '#', 103, 1, NOW(), NULL, NULL, '批量抽票任务新增权限'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 21012)
  AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'ticket:lotteryBatchTask:add');

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 21013, '批量抽票修改', 20013, 3, '', '', '', 1, 0, 'F', '0', '0', 'ticket:lotteryBatchTask:edit', '#', 103, 1, NOW(), NULL, NULL, '批量抽票任务修改权限'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 21013)
  AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'ticket:lotteryBatchTask:edit');

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 21014, '批量抽票执行', 20013, 4, '', '', '', 1, 0, 'F', '0', '0', 'ticket:lotteryBatchTask:execute', '#', 103, 1, NOW(), NULL, NULL, '批量抽票任务执行权限'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 21014)
  AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'ticket:lotteryBatchTask:execute');

UPDATE `sys_menu`
SET `menu_name` = '批量抽票查询',
    `parent_id` = 20013,
    `order_num` = 1,
    `menu_type` = 'F',
    `visible` = '0',
    `status` = '0',
    `perms` = 'ticket:lotteryBatchTask:query',
    `remark` = '批量抽票任务查询权限',
    `update_time` = NOW()
WHERE `menu_id` = 21011
   OR `perms` = 'ticket:lotteryBatchTask:query';

UPDATE `sys_menu`
SET `menu_name` = '批量抽票新增',
    `parent_id` = 20013,
    `order_num` = 2,
    `menu_type` = 'F',
    `visible` = '0',
    `status` = '0',
    `perms` = 'ticket:lotteryBatchTask:add',
    `remark` = '批量抽票任务新增权限',
    `update_time` = NOW()
WHERE `menu_id` = 21012
   OR `perms` = 'ticket:lotteryBatchTask:add';

UPDATE `sys_menu`
SET `menu_name` = '批量抽票修改',
    `parent_id` = 20013,
    `order_num` = 3,
    `menu_type` = 'F',
    `visible` = '0',
    `status` = '0',
    `perms` = 'ticket:lotteryBatchTask:edit',
    `remark` = '批量抽票任务修改权限',
    `update_time` = NOW()
WHERE `menu_id` = 21013
   OR `perms` = 'ticket:lotteryBatchTask:edit';

UPDATE `sys_menu`
SET `menu_name` = '批量抽票执行',
    `parent_id` = 20013,
    `order_num` = 4,
    `menu_type` = 'F',
    `visible` = '0',
    `status` = '0',
    `perms` = 'ticket:lotteryBatchTask:execute',
    `remark` = '批量抽票任务执行权限',
    `update_time` = NOW()
WHERE `menu_id` = 21014
   OR `perms` = 'ticket:lotteryBatchTask:execute';

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20013
FROM dual
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20013)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20013);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 21011
FROM dual
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 21011)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 21011);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 21012
FROM dual
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 21012)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 21012);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 21013
FROM dual
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 21013)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 21013);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 21014
FROM dual
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 21014)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 21014);
