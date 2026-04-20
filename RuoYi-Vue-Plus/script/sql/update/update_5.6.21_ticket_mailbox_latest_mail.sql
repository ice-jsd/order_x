ALTER TABLE `ticket_mailbox_account`
  ADD COLUMN `latest_mail_subject` varchar(1000) DEFAULT NULL COMMENT '最新邮件标题' AFTER `last_error`,
  ADD COLUMN `latest_mail_from` varchar(500) DEFAULT NULL COMMENT '最新邮件发件人' AFTER `latest_mail_subject`,
  ADD COLUMN `latest_mail_received_at` datetime DEFAULT NULL COMMENT '最新邮件收件时间' AFTER `latest_mail_from`,
  ADD COLUMN `latest_mail_message_id` varchar(255) DEFAULT NULL COMMENT '最新邮件Message-ID' AFTER `latest_mail_received_at`,
  ADD COLUMN `latest_mail_excerpt` varchar(2000) DEFAULT NULL COMMENT '最新邮件正文摘要' AFTER `latest_mail_message_id`,
  ADD COLUMN `latest_verify_code` varchar(32) DEFAULT NULL COMMENT '最新验证码' AFTER `latest_mail_excerpt`,
  ADD COLUMN `latest_activation_url` longtext COMMENT '最新激活链接' AFTER `latest_verify_code`,
  ADD COLUMN `last_mail_sync_time` datetime DEFAULT NULL COMMENT '最近邮件同步时间' AFTER `latest_activation_url`,
  ADD COLUMN `last_mail_sync_error` varchar(1000) DEFAULT NULL COMMENT '最近邮件同步错误' AFTER `last_mail_sync_time`;

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20904, '邮箱同步', 20010, 4, '', '', '', 1, 0, 'F', '0', '0', 'ticket:mailbox:sync', '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20904);
