SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `ticket_mail_forward_record` (
  `forward_id` bigint(20) NOT NULL COMMENT '转发主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `record_id` bigint(20) NOT NULL COMMENT '源邮件记录ID',
  `mailbox_id` bigint(20) DEFAULT NULL COMMENT '源邮箱ID',
  `source_email` varchar(255) DEFAULT NULL COMMENT '源邮箱地址',
  `source_message_id` varchar(255) DEFAULT NULL COMMENT '源邮件Message-ID',
  `mail_subject` varchar(1000) DEFAULT NULL COMMENT '原邮件标题',
  `target_email` varchar(255) NOT NULL COMMENT '目标邮箱地址',
  `sender_from` varchar(500) DEFAULT NULL COMMENT '发件人标识',
  `forward_message_id` varchar(255) DEFAULT NULL COMMENT '转发邮件Message-ID',
  `send_status` varchar(32) NOT NULL COMMENT '发送状态',
  `error_message` varchar(2000) DEFAULT NULL COMMENT '失败原因',
  `forward_subject` varchar(1000) DEFAULT NULL COMMENT '转发主题',
  `forward_content` longtext COMMENT '转发正文',
  `operator_name` varchar(255) DEFAULT NULL COMMENT '操作人名称',
  `sent_at` datetime DEFAULT NULL COMMENT '发送时间',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  PRIMARY KEY (`forward_id`),
  KEY `idx_ticket_mail_forward_record_record` (`record_id`),
  KEY `idx_ticket_mail_forward_record_target_email` (`target_email`),
  KEY `idx_ticket_mail_forward_record_send_status` (`send_status`),
  KEY `idx_ticket_mail_forward_record_sent_at` (`sent_at`),
  KEY `idx_ticket_mail_forward_record_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='邮件转发记录';

INSERT INTO `sys_menu`
(
    `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`,
    `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`,
    `create_time`, `update_by`, `update_time`, `remark`
)
SELECT 20015, '邮件转发记录', 20000, 10, 'mail-forward', 'ticket/mail-forward/index', '', 1, 0, 'C', '0', '0',
       'ticket:mailForward:list', 'mail', 103, 1, NOW(), NULL, NULL, '邮件转发记录页'
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20015);

UPDATE `sys_menu`
SET `menu_name` = '邮件转发记录',
    `parent_id` = 20000,
    `order_num` = 10,
    `path` = 'mail-forward',
    `component` = 'ticket/mail-forward/index',
    `menu_type` = 'C',
    `visible` = '0',
    `status` = '0',
    `perms` = 'ticket:mailForward:list',
    `icon` = 'mail',
    `remark` = '邮件转发记录页'
WHERE `menu_id` = 20015;

INSERT INTO `sys_menu`
(
    `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`,
    `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`,
    `create_time`, `update_by`, `update_time`, `remark`
)
SELECT 20905, '邮件转发发送', 20014, 1, '', '', '', 1, 0, 'F', '0', '0',
       'ticket:mailForward:send', '#', 103, 1, NOW(), NULL, NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20905);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20015
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20015)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20015);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20905
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20905)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20905);
