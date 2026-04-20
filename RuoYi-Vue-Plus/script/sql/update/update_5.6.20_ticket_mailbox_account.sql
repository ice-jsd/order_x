CREATE TABLE IF NOT EXISTS `ticket_mailbox_account` (
  `mailbox_id` bigint(20) NOT NULL COMMENT '邮箱账号主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `email` varchar(255) NOT NULL COMMENT '邮箱地址',
  `username` varchar(255) NOT NULL COMMENT '邮箱用户名',
  `password` varchar(255) NOT NULL COMMENT '邮箱密码',
  `domain` varchar(120) NOT NULL COMMENT '邮箱域名',
  `provider` varchar(32) DEFAULT 'stalwart' COMMENT '邮箱服务商',
  `stalwart_principal_id` varchar(128) DEFAULT NULL COMMENT 'Stalwart Principal ID',
  `status` varchar(32) DEFAULT 'available' COMMENT '邮箱状态',
  `used_account_id` bigint(20) DEFAULT NULL COMMENT '使用该邮箱的平台账号',
  `used_time` datetime DEFAULT NULL COMMENT '使用时间',
  `last_error` varchar(1000) DEFAULT NULL COMMENT '最近错误',
  `latest_mail_subject` varchar(1000) DEFAULT NULL COMMENT '最新邮件标题',
  `latest_mail_from` varchar(500) DEFAULT NULL COMMENT '最新邮件发件人',
  `latest_mail_received_at` datetime DEFAULT NULL COMMENT '最新邮件收件时间',
  `latest_mail_message_id` varchar(255) DEFAULT NULL COMMENT '最新邮件Message-ID',
  `latest_mail_excerpt` varchar(2000) DEFAULT NULL COMMENT '最新邮件正文摘要',
  `latest_verify_code` varchar(32) DEFAULT NULL COMMENT '最新验证码',
  `latest_activation_url` longtext COMMENT '最新激活链接',
  `last_mail_sync_time` datetime DEFAULT NULL COMMENT '最近邮件同步时间',
  `last_mail_sync_error` varchar(1000) DEFAULT NULL COMMENT '最近邮件同步错误',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`mailbox_id`),
  UNIQUE KEY `uk_ticket_mailbox_email` (`email`, `del_flag`),
  KEY `idx_ticket_mailbox_status` (`status`),
  KEY `idx_ticket_mailbox_used_account` (`used_account_id`),
  KEY `idx_ticket_mailbox_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮箱账号池表';

DELETE FROM `sys_role_menu`
WHERE `menu_id` IN (
  SELECT `menu_id` FROM `sys_menu`
  WHERE `perms` LIKE 'ticket:mail:%'
     OR `component` = 'ticket/mail/index'
     OR `menu_id` IN (20010, 20901, 20902, 20903, 20904)
);

DELETE FROM `sys_menu`
WHERE `perms` LIKE 'ticket:mail:%'
   OR `component` = 'ticket/mail/index'
   OR `menu_id` IN (20010, 20901, 20902, 20903, 20904);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20010, '邮箱账号池', 20000, 4, 'mailbox-account', 'ticket/mailbox-account/index', '', 1, 0, 'C', '0', '0', 'ticket:mailbox:list', 'mail', 103, 1, SYSDATE(), NULL, NULL, '邮箱账号池菜单'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20010);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20901, '邮箱查询', 20010, 1, '', '', '', 1, 0, 'F', '0', '0', 'ticket:mailbox:list', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20901);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20902, '邮箱创建', 20010, 2, '', '', '', 1, 0, 'F', '0', '0', 'ticket:mailbox:create', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20902);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20903, '邮箱状态修改', 20010, 3, '', '', '', 1, 0, 'F', '0', '0', 'ticket:mailbox:edit', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20903);

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20904, '邮箱同步', 20010, 4, '', '', '', 1, 0, 'F', '0', '0', 'ticket:mailbox:sync', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20904);
