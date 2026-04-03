-- ----------------------------
-- 票务运营模块建表
-- ----------------------------

CREATE TABLE IF NOT EXISTS `ticket_platform_config` (
  `platform_id` bigint(20) NOT NULL COMMENT '平台主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `platform_code` varchar(64) NOT NULL COMMENT '平台编码',
  `platform_name` varchar(120) NOT NULL COMMENT '平台名称',
  `adapter_type` varchar(32) DEFAULT 'mock' COMMENT '适配器类型',
  `environment` varchar(32) DEFAULT 'sandbox' COMMENT '环境',
  `enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用',
  `supports_batch_register` tinyint(1) DEFAULT 1 COMMENT '支持批量注册',
  `supports_batch_login` tinyint(1) DEFAULT 1 COMMENT '支持批量登录',
  `supports_sms` tinyint(1) DEFAULT 0 COMMENT '支持短信',
  `supports_email` tinyint(1) DEFAULT 1 COMMENT '支持邮箱',
  `supports_phone_identity` tinyint(1) DEFAULT 1 COMMENT '支持号码身份',
  `callback_url` varchar(500) DEFAULT NULL COMMENT '回调地址',
  `callback_secret_mask` varchar(255) DEFAULT NULL COMMENT '回调密钥摘要',
  `registration_template` text COMMENT '注册模板',
  `login_strategy` text COMMENT '登录策略',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`platform_id`),
  UNIQUE KEY `uk_ticket_platform_code` (`platform_code`, `del_flag`),
  KEY `idx_ticket_platform_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='票务平台配置表';

CREATE TABLE IF NOT EXISTS `ticket_phone_number` (
  `phone_id` bigint(20) NOT NULL COMMENT '号码主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `phone_number` varchar(64) NOT NULL COMMENT '号码',
  `country_code` varchar(16) DEFAULT NULL COMMENT '国家区号',
  `supplier` varchar(64) DEFAULT NULL COMMENT '供应商',
  `status` varchar(32) DEFAULT 'available' COMMENT '号码状态',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`phone_id`),
  UNIQUE KEY `uk_ticket_phone_number` (`phone_number`, `del_flag`),
  KEY `idx_ticket_phone_supplier` (`supplier`),
  KEY `idx_ticket_phone_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='号码池表';

CREATE TABLE IF NOT EXISTS `ticket_phone_platform_relation` (
  `relation_id` bigint(20) NOT NULL COMMENT '关系主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `phone_id` bigint(20) NOT NULL COMMENT '号码主键',
  `platform_id` bigint(20) NOT NULL COMMENT '平台主键',
  `account_id` bigint(20) DEFAULT NULL COMMENT '账号主键',
  `status` varchar(32) DEFAULT 'available' COMMENT '关系状态',
  `last_error` varchar(500) DEFAULT NULL COMMENT '最近错误',
  `last_operate_time` datetime DEFAULT NULL COMMENT '最近操作时间',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`relation_id`),
  UNIQUE KEY `uk_ticket_relation_phone_platform` (`platform_id`, `phone_id`, `del_flag`),
  KEY `idx_ticket_relation_account` (`account_id`),
  KEY `idx_ticket_relation_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='号码平台关系表';

CREATE TABLE IF NOT EXISTS `ticket_managed_account` (
  `account_id` bigint(20) NOT NULL COMMENT '账号主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `platform_id` bigint(20) NOT NULL COMMENT '平台主键',
  `phone_id` bigint(20) DEFAULT NULL COMMENT '号码主键',
  `account_no` varchar(128) NOT NULL COMMENT '账号编号',
  `display_name` varchar(120) DEFAULT NULL COMMENT '展示名',
  `account_status` varchar(32) DEFAULT 'registered' COMMENT '账号状态',
  `login_status` varchar(32) DEFAULT 'offline' COMMENT '登录状态',
  `session_token` varchar(1024) DEFAULT NULL COMMENT '会话令牌',
  `session_expire_time` datetime DEFAULT NULL COMMENT '会话过期时间',
  `last_login_time` datetime DEFAULT NULL COMMENT '最近登录时间',
  `last_error` varchar(500) DEFAULT NULL COMMENT '最近错误',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`account_id`),
  KEY `idx_ticket_account_platform` (`platform_id`),
  KEY `idx_ticket_account_phone` (`phone_id`),
  KEY `idx_ticket_account_login_status` (`login_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台账号池表';

CREATE TABLE IF NOT EXISTS `ticket_registration_batch` (
  `batch_id` bigint(20) NOT NULL COMMENT '批次主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `platform_id` bigint(20) NOT NULL COMMENT '平台主键',
  `batch_no` varchar(128) NOT NULL COMMENT '批次号',
  `batch_status` varchar(32) DEFAULT 'draft' COMMENT '批次状态',
  `total_count` int(11) DEFAULT 0 COMMENT '总数',
  `success_count` int(11) DEFAULT 0 COMMENT '成功数',
  `skipped_count` int(11) DEFAULT 0 COMMENT '跳过数',
  `failed_count` int(11) DEFAULT 0 COMMENT '失败数',
  `result_summary` longtext COMMENT '结果摘要',
  `executed_at` datetime DEFAULT NULL COMMENT '执行时间',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`batch_id`),
  UNIQUE KEY `uk_ticket_registration_batch_no` (`batch_no`, `del_flag`),
  KEY `idx_ticket_registration_platform` (`platform_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='注册批次表';

CREATE TABLE IF NOT EXISTS `ticket_registration_batch_detail` (
  `detail_id` bigint(20) NOT NULL COMMENT '明细主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `batch_id` bigint(20) NOT NULL COMMENT '注册批次主键',
  `phone_id` bigint(20) NOT NULL COMMENT '号码主键',
  `platform_id` bigint(20) NOT NULL COMMENT '平台主键',
  `execute_status` varchar(32) DEFAULT 'processing' COMMENT '执行状态',
  `result_message` varchar(500) DEFAULT NULL COMMENT '返回信息',
  `account_id` bigint(20) DEFAULT NULL COMMENT '账号主键',
  `account_no` varchar(128) DEFAULT NULL COMMENT '账号编号',
  `executed_at` datetime DEFAULT NULL COMMENT '执行时间',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`detail_id`),
  KEY `idx_ticket_reg_detail_batch` (`batch_id`),
  KEY `idx_ticket_reg_detail_phone` (`phone_id`),
  KEY `idx_ticket_reg_detail_status` (`execute_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='注册批次明细表';

CREATE TABLE IF NOT EXISTS `ticket_login_batch` (
  `batch_id` bigint(20) NOT NULL COMMENT '批次主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `platform_id` bigint(20) NOT NULL COMMENT '平台主键',
  `batch_no` varchar(128) NOT NULL COMMENT '批次号',
  `batch_status` varchar(32) DEFAULT 'draft' COMMENT '批次状态',
  `total_count` int(11) DEFAULT 0 COMMENT '总数',
  `success_count` int(11) DEFAULT 0 COMMENT '成功数',
  `failed_count` int(11) DEFAULT 0 COMMENT '失败数',
  `result_summary` longtext COMMENT '结果摘要',
  `executed_at` datetime DEFAULT NULL COMMENT '执行时间',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`batch_id`),
  UNIQUE KEY `uk_ticket_login_batch_no` (`batch_no`, `del_flag`),
  KEY `idx_ticket_login_platform` (`platform_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录批次表';

CREATE TABLE IF NOT EXISTS `ticket_event_config` (
  `event_id` bigint(20) NOT NULL COMMENT '活动主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `platform_id` bigint(20) NOT NULL COMMENT '平台主键',
  `event_code` varchar(64) NOT NULL COMMENT '活动编码',
  `event_name` varchar(150) NOT NULL COMMENT '活动名称',
  `sale_time` datetime DEFAULT NULL COMMENT '开售时间',
  `event_status` varchar(32) DEFAULT 'draft' COMMENT '活动状态',
  `inventory_policy` text COMMENT '库存策略',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uk_ticket_event_code` (`platform_id`, `event_code`, `del_flag`),
  KEY `idx_ticket_event_status` (`event_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动配置表';

CREATE TABLE IF NOT EXISTS `ticket_sale_task` (
  `task_id` bigint(20) NOT NULL COMMENT '任务主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `platform_id` bigint(20) NOT NULL COMMENT '平台主键',
  `event_id` bigint(20) NOT NULL COMMENT '活动主键',
  `task_name` varchar(150) NOT NULL COMMENT '任务名称',
  `task_mode` varchar(32) DEFAULT 'manual_confirm' COMMENT '任务模式',
  `task_status` varchar(32) DEFAULT 'draft' COMMENT '任务状态',
  `warmup_time` datetime DEFAULT NULL COMMENT '预热时间',
  `scheduled_time` datetime DEFAULT NULL COMMENT '计划执行时间',
  `last_executed_time` datetime DEFAULT NULL COMMENT '最近执行时间',
  `rule_config` longtext COMMENT '规则配置',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`task_id`),
  KEY `idx_ticket_sale_task_platform` (`platform_id`),
  KEY `idx_ticket_sale_task_event` (`event_id`),
  KEY `idx_ticket_sale_task_status` (`task_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售任务表';

CREATE TABLE IF NOT EXISTS `ticket_order_execution` (
  `execution_id` bigint(20) NOT NULL COMMENT '执行主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `task_id` bigint(20) NOT NULL COMMENT '任务主键',
  `platform_id` bigint(20) NOT NULL COMMENT '平台主键',
  `account_id` bigint(20) DEFAULT NULL COMMENT '账号主键',
  `order_no` varchar(128) DEFAULT NULL COMMENT '订单号',
  `execution_status` varchar(32) DEFAULT 'blocked' COMMENT '执行状态',
  `result_message` varchar(500) DEFAULT NULL COMMENT '结果信息',
  `executed_at` datetime DEFAULT NULL COMMENT '执行时间',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`execution_id`),
  KEY `idx_ticket_order_task` (`task_id`),
  KEY `idx_ticket_order_platform` (`platform_id`),
  KEY `idx_ticket_order_status` (`execution_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单执行表';

CREATE TABLE IF NOT EXISTS `ticket_audit_event` (
  `audit_id` bigint(20) NOT NULL COMMENT '审计主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `module_name` varchar(64) NOT NULL COMMENT '模块名称',
  `action_type` varchar(64) DEFAULT NULL COMMENT '动作类型',
  `business_type` varchar(64) DEFAULT NULL COMMENT '业务类型',
  `business_key` varchar(128) DEFAULT NULL COMMENT '业务主键',
  `audit_status` varchar(32) DEFAULT 'success' COMMENT '审计状态',
  `message` varchar(500) DEFAULT NULL COMMENT '消息',
  `payload` longtext COMMENT '载荷',
  `event_time` datetime DEFAULT NULL COMMENT '事件时间',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`audit_id`),
  KEY `idx_ticket_audit_module` (`module_name`),
  KEY `idx_ticket_audit_status` (`audit_status`),
  KEY `idx_ticket_audit_time` (`event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='票务审计日志表';

-- ----------------------------
-- 票务运营菜单
-- ----------------------------

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20000, '票务运营', 0, 6, 'ticket', NULL, '', 1, 0, 'M', '0', '0', '', 'dashboard', 103, 1, SYSDATE(), NULL, NULL, '票务运营目录'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20000);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20001, '平台接入', 20000, 1, 'platform', 'ticket/platform/index', '', 1, 0, 'C', '0', '0', 'ticket:platform:list', 'tool', 103, 1, SYSDATE(), NULL, NULL, '平台接入菜单'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20001);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20002, '号码池', 20000, 2, 'phone', 'ticket/phone/index', '', 1, 0, 'C', '0', '0', 'ticket:phone:list', 'phone', 103, 1, SYSDATE(), NULL, NULL, '号码池菜单'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20002);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20003, '账号池', 20000, 3, 'account', 'ticket/account/index', '', 1, 0, 'C', '0', '0', 'ticket:account:list', 'user', 103, 1, SYSDATE(), NULL, NULL, '账号池菜单'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20003);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20004, '注册批次', 20000, 4, 'registration-batch', 'ticket/registration-batch/index', '', 1, 0, 'C', '0', '0', 'ticket:registrationBatch:list', 'form', 103, 1, SYSDATE(), NULL, NULL, '注册批次菜单'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20004);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20005, '登录批次', 20000, 5, 'login-batch', 'ticket/login-batch/index', '', 1, 0, 'C', '0', '0', 'ticket:loginBatch:list', 'log', 103, 1, SYSDATE(), NULL, NULL, '登录批次菜单'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20005);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20006, '活动配置', 20000, 6, 'event', 'ticket/event/index', '', 1, 0, 'C', '0', '0', 'ticket:event:list', 'date', 103, 1, SYSDATE(), NULL, NULL, '活动配置菜单'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20006);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20007, '销售任务', 20000, 7, 'sale-task', 'ticket/sale-task/index', '', 1, 0, 'C', '0', '0', 'ticket:saleTask:list', 'job', 103, 1, SYSDATE(), NULL, NULL, '销售任务菜单'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20007);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20008, '订单执行', 20000, 8, 'order-execution', 'ticket/order-execution/index', '', 1, 0, 'C', '0', '0', 'ticket:orderExecution:list', 'list', 103, 1, SYSDATE(), NULL, NULL, '订单执行菜单'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20008);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20009, '审计中心', 20000, 9, 'audit-log', 'ticket/audit-log/index', '', 1, 0, 'C', '0', '0', 'ticket:audit:list', 'monitor', 103, 1, SYSDATE(), NULL, NULL, '审计中心菜单'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20009);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20101, '平台查询', 20001, 1, '', '', '', 1, 0, 'F', '0', '0', 'ticket:platform:query', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20101);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20102, '平台新增', 20001, 2, '', '', '', 1, 0, 'F', '0', '0', 'ticket:platform:add', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20102);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20103, '平台修改', 20001, 3, '', '', '', 1, 0, 'F', '0', '0', 'ticket:platform:edit', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20103);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20104, '平台删除', 20001, 4, '', '', '', 1, 0, 'F', '0', '0', 'ticket:platform:remove', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20104);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20105, '平台批量注册', 20001, 5, '', '', '', 1, 0, 'F', '0', '0', 'ticket:platform:register', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20105);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20201, '号码导入', 20002, 1, '', '', '', 1, 0, 'F', '0', '0', 'ticket:phone:import', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20201);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20202, '号码修改', 20002, 2, '', '', '', 1, 0, 'F', '0', '0', 'ticket:phone:edit', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20202);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20203, '关系查询', 20002, 3, '', '', '', 1, 0, 'F', '0', '0', 'ticket:relation:list', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20203);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20301, '账号批量登录', 20003, 1, '', '', '', 1, 0, 'F', '0', '0', 'ticket:account:login', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20301);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20601, '活动查询', 20006, 1, '', '', '', 1, 0, 'F', '0', '0', 'ticket:event:query', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20601);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20602, '活动新增', 20006, 2, '', '', '', 1, 0, 'F', '0', '0', 'ticket:event:add', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20602);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20603, '活动修改', 20006, 3, '', '', '', 1, 0, 'F', '0', '0', 'ticket:event:edit', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20603);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20604, '活动删除', 20006, 4, '', '', '', 1, 0, 'F', '0', '0', 'ticket:event:remove', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20604);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20701, '任务查询', 20007, 1, '', '', '', 1, 0, 'F', '0', '0', 'ticket:saleTask:query', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20701);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20702, '任务新增', 20007, 2, '', '', '', 1, 0, 'F', '0', '0', 'ticket:saleTask:add', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20702);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20703, '任务修改', 20007, 3, '', '', '', 1, 0, 'F', '0', '0', 'ticket:saleTask:edit', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20703);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20704, '任务删除', 20007, 4, '', '', '', 1, 0, 'F', '0', '0', 'ticket:saleTask:remove', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20704);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20705, '任务执行', 20007, 5, '', '', '', 1, 0, 'F', '0', '0', 'ticket:saleTask:execute', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20705);
