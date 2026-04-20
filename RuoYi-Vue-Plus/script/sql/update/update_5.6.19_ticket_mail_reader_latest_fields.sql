-- 自建邮箱最新邮件解析结果字段

SET @latest_verify_code_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_managed_account'
    AND column_name = 'latest_verify_code'
);
SET @latest_verify_code_sql := IF(
  @latest_verify_code_exists = 0,
  'ALTER TABLE `ticket_managed_account` ADD COLUMN `latest_verify_code` varchar(32) DEFAULT NULL COMMENT ''最新验证码'' AFTER `last_error`',
  'SELECT 1'
);
PREPARE stmt_latest_verify_code FROM @latest_verify_code_sql;
EXECUTE stmt_latest_verify_code;
DEALLOCATE PREPARE stmt_latest_verify_code;

SET @latest_activation_url_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_managed_account'
    AND column_name = 'latest_activation_url'
);
SET @latest_activation_url_sql := IF(
  @latest_activation_url_exists = 0,
  'ALTER TABLE `ticket_managed_account` ADD COLUMN `latest_activation_url` longtext COMMENT ''最新激活链接'' AFTER `latest_verify_code`',
  'SELECT 1'
);
PREPARE stmt_latest_activation_url FROM @latest_activation_url_sql;
EXECUTE stmt_latest_activation_url;
DEALLOCATE PREPARE stmt_latest_activation_url;

SET @latest_mail_subject_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_managed_account'
    AND column_name = 'latest_mail_subject'
);
SET @latest_mail_subject_sql := IF(
  @latest_mail_subject_exists = 0,
  'ALTER TABLE `ticket_managed_account` ADD COLUMN `latest_mail_subject` varchar(1000) DEFAULT NULL COMMENT ''最新邮件标题'' AFTER `latest_activation_url`',
  'SELECT 1'
);
PREPARE stmt_latest_mail_subject FROM @latest_mail_subject_sql;
EXECUTE stmt_latest_mail_subject;
DEALLOCATE PREPARE stmt_latest_mail_subject;

SET @latest_mail_received_at_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_managed_account'
    AND column_name = 'latest_mail_received_at'
);
SET @latest_mail_received_at_sql := IF(
  @latest_mail_received_at_exists = 0,
  'ALTER TABLE `ticket_managed_account` ADD COLUMN `latest_mail_received_at` datetime DEFAULT NULL COMMENT ''最新邮件收件时间'' AFTER `latest_mail_subject`',
  'SELECT 1'
);
PREPARE stmt_latest_mail_received_at FROM @latest_mail_received_at_sql;
EXECUTE stmt_latest_mail_received_at;
DEALLOCATE PREPARE stmt_latest_mail_received_at;

SET @latest_mail_message_id_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_managed_account'
    AND column_name = 'latest_mail_message_id'
);
SET @latest_mail_message_id_sql := IF(
  @latest_mail_message_id_exists = 0,
  'ALTER TABLE `ticket_managed_account` ADD COLUMN `latest_mail_message_id` varchar(255) DEFAULT NULL COMMENT ''最新邮件Message-ID'' AFTER `latest_mail_received_at`',
  'SELECT 1'
);
PREPARE stmt_latest_mail_message_id FROM @latest_mail_message_id_sql;
EXECUTE stmt_latest_mail_message_id;
DEALLOCATE PREPARE stmt_latest_mail_message_id;
