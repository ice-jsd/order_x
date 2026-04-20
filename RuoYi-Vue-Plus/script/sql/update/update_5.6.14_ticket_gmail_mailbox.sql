-- ----------------------------
-- Gmail 转发邮件关联账号能力
-- ----------------------------

SET @mail_alias_column_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_managed_account'
    AND column_name = 'mail_alias'
);
SET @mail_alias_column_sql := IF(
  @mail_alias_column_exists = 0,
  'ALTER TABLE `ticket_managed_account` ADD COLUMN `mail_alias` varchar(255) DEFAULT NULL COMMENT ''业务邮箱 alias'' AFTER `email`',
  'SELECT 1'
);
PREPARE stmt_mail_alias_column FROM @mail_alias_column_sql;
EXECUTE stmt_mail_alias_column;
DEALLOCATE PREPARE stmt_mail_alias_column;

SET @mail_alias_index_exists := (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_managed_account'
    AND index_name = 'uk_ticket_account_mail_alias'
);
SET @mail_alias_index_sql := IF(
  @mail_alias_index_exists = 0,
  'ALTER TABLE `ticket_managed_account` ADD UNIQUE KEY `uk_ticket_account_mail_alias` (`tenant_id`, `mail_alias`, `del_flag`)',
  'SELECT 1'
);
PREPARE stmt_mail_alias_index FROM @mail_alias_index_sql;
EXECUTE stmt_mail_alias_index;
DEALLOCATE PREPARE stmt_mail_alias_index;

CREATE TABLE IF NOT EXISTS `ticket_gmail_config` (
  `config_id` bigint(20) NOT NULL COMMENT '邮件接入配置主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `gmail_address` varchar(255) DEFAULT NULL COMMENT '收件箱地址',
  `provider_type` varchar(32) DEFAULT 'gmail_api' COMMENT '接入类型',
  `client_id` varchar(500) DEFAULT NULL COMMENT 'OAuth Client ID',
  `client_secret` varchar(1000) DEFAULT NULL COMMENT 'OAuth Client Secret',
  `access_token` longtext COMMENT 'OAuth Access Token',
  `refresh_token` longtext COMMENT 'OAuth Refresh Token',
  `scope` varchar(1000) DEFAULT NULL COMMENT 'OAuth Scope',
  `sync_query` varchar(500) DEFAULT NULL COMMENT '邮件同步查询条件',
  `imap_host` varchar(255) DEFAULT NULL COMMENT 'IMAP主机',
  `imap_port` int DEFAULT NULL COMMENT 'IMAP端口',
  `imap_ssl` tinyint(1) DEFAULT 1 COMMENT 'IMAP是否启用SSL',
  `imap_username` varchar(255) DEFAULT NULL COMMENT 'IMAP用户名',
  `imap_auth_secret` varchar(1000) DEFAULT NULL COMMENT 'IMAP授权码',
  `imap_folder` varchar(255) DEFAULT NULL COMMENT 'IMAP文件夹',
  `enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用',
  `last_sync_at` datetime DEFAULT NULL COMMENT '最近同步时间',
  `last_message_internal_time` bigint(20) DEFAULT NULL COMMENT '最近邮件内部时间',
  `last_error` varchar(1000) DEFAULT NULL COMMENT '最近错误',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`config_id`),
  KEY `idx_ticket_gmail_config_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮件接入配置表';

CREATE TABLE IF NOT EXISTS `ticket_mail_record` (
  `mail_record_id` bigint(20) NOT NULL COMMENT '邮件流水主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `gmail_message_id` varchar(255) NOT NULL COMMENT 'Gmail邮件ID',
  `gmail_thread_id` varchar(255) DEFAULT NULL COMMENT 'Gmail线程ID',
  `provider_type` varchar(32) DEFAULT 'gmail_api' COMMENT '接入类型',
  `account_id` bigint(20) DEFAULT NULL COMMENT '账号主键',
  `mail_alias` varchar(255) DEFAULT NULL COMMENT '业务邮箱 alias',
  `from_address` varchar(500) DEFAULT NULL COMMENT '发件人',
  `subject` varchar(1000) DEFAULT NULL COMMENT '主题',
  `received_at` datetime DEFAULT NULL COMMENT '收件时间',
  `matched_header` varchar(64) DEFAULT NULL COMMENT '命中的头部字段',
  `header_snapshot` longtext COMMENT '头部快照(JSON)',
  `body_excerpt` longtext COMMENT '正文摘要',
  `verification_code` varchar(32) DEFAULT NULL COMMENT '验证码',
  `activation_url` longtext COMMENT '激活链接',
  `parse_status` varchar(32) DEFAULT NULL COMMENT '解析状态',
  `process_status` varchar(32) DEFAULT NULL COMMENT '处理状态',
  `error_message` varchar(1000) DEFAULT NULL COMMENT '错误信息',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`mail_record_id`),
  UNIQUE KEY `uk_ticket_mail_record_gmail_message` (`tenant_id`, `gmail_message_id`, `del_flag`),
  KEY `idx_ticket_mail_record_account` (`account_id`),
  KEY `idx_ticket_mail_record_alias` (`mail_alias`),
  KEY `idx_ticket_mail_record_received_at` (`received_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮件流水表';

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20010, '邮件接入配置', 20000, 10, 'mail', 'ticket/mail/index', '', 1, 0, 'C', '0', '0', 'ticket:mail:config', 'mail', 103, 1, NOW(), NULL, NULL, '邮件接入配置菜单'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20010);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20901, '邮件配置', 20010, 1, '', '', '', 1, 0, 'F', '0', '0', 'ticket:mail:config', '#', 103, 1, NOW(), NULL, NULL, '邮件配置权限'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20901);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20902, '邮件同步', 20010, 2, '', '', '', 1, 0, 'F', '0', '0', 'ticket:mail:sync', '#', 103, 1, NOW(), NULL, NULL, '邮件同步权限'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20902);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20903, '邮件查询', 20010, 3, '', '', '', 1, 0, 'F', '0', '0', 'ticket:mail:query', '#', 103, 1, NOW(), NULL, NULL, '邮件查询权限'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20903);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20010
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20010);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20901
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20901);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20902
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20902);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20903
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20903);

UPDATE `sys_menu` SET `parent_id` = 20010 WHERE `menu_id` IN (20901, 20902, 20903);
