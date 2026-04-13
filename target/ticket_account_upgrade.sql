ALTER TABLE `ticket_registration_batch_detail`
  ADD COLUMN `email` varchar(255) DEFAULT NULL COMMENT '账号邮箱' AFTER `account_id`,
  DROP COLUMN `account_no`;

ALTER TABLE `ticket_login_batch_detail`
  ADD COLUMN `req_data` longtext COMMENT '请求上下文(JSON)' AFTER `result_message`,
  DROP COLUMN `session_token`,
  DROP COLUMN `session_expire_time`;

ALTER TABLE `ticket_managed_account`
  ADD UNIQUE KEY `uk_ticket_account_platform_email` (`platform_id`, `email`, `del_flag`);
