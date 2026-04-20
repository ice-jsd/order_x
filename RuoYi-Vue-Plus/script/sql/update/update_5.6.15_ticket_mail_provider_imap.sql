-- ----------------------------
-- 邮件接入 provider 化，新增 IMAP 支持
-- ----------------------------

SET @mail_config_provider_type_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_gmail_config'
    AND column_name = 'provider_type'
);
SET @mail_config_provider_type_sql := IF(
  @mail_config_provider_type_exists = 0,
  'ALTER TABLE `ticket_gmail_config` ADD COLUMN `provider_type` varchar(32) DEFAULT ''gmail_api'' COMMENT ''接入类型'' AFTER `gmail_address`',
  'SELECT 1'
);
PREPARE stmt_mail_config_provider_type FROM @mail_config_provider_type_sql;
EXECUTE stmt_mail_config_provider_type;
DEALLOCATE PREPARE stmt_mail_config_provider_type;

SET @mail_config_imap_host_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_gmail_config'
    AND column_name = 'imap_host'
);
SET @mail_config_imap_host_sql := IF(
  @mail_config_imap_host_exists = 0,
  'ALTER TABLE `ticket_gmail_config` ADD COLUMN `imap_host` varchar(255) DEFAULT NULL COMMENT ''IMAP主机'' AFTER `sync_query`',
  'SELECT 1'
);
PREPARE stmt_mail_config_imap_host FROM @mail_config_imap_host_sql;
EXECUTE stmt_mail_config_imap_host;
DEALLOCATE PREPARE stmt_mail_config_imap_host;

SET @mail_config_imap_port_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_gmail_config'
    AND column_name = 'imap_port'
);
SET @mail_config_imap_port_sql := IF(
  @mail_config_imap_port_exists = 0,
  'ALTER TABLE `ticket_gmail_config` ADD COLUMN `imap_port` int DEFAULT NULL COMMENT ''IMAP端口'' AFTER `imap_host`',
  'SELECT 1'
);
PREPARE stmt_mail_config_imap_port FROM @mail_config_imap_port_sql;
EXECUTE stmt_mail_config_imap_port;
DEALLOCATE PREPARE stmt_mail_config_imap_port;

SET @mail_config_imap_ssl_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_gmail_config'
    AND column_name = 'imap_ssl'
);
SET @mail_config_imap_ssl_sql := IF(
  @mail_config_imap_ssl_exists = 0,
  'ALTER TABLE `ticket_gmail_config` ADD COLUMN `imap_ssl` tinyint(1) DEFAULT 1 COMMENT ''IMAP是否启用SSL'' AFTER `imap_port`',
  'SELECT 1'
);
PREPARE stmt_mail_config_imap_ssl FROM @mail_config_imap_ssl_sql;
EXECUTE stmt_mail_config_imap_ssl;
DEALLOCATE PREPARE stmt_mail_config_imap_ssl;

SET @mail_config_imap_username_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_gmail_config'
    AND column_name = 'imap_username'
);
SET @mail_config_imap_username_sql := IF(
  @mail_config_imap_username_exists = 0,
  'ALTER TABLE `ticket_gmail_config` ADD COLUMN `imap_username` varchar(255) DEFAULT NULL COMMENT ''IMAP用户名'' AFTER `imap_ssl`',
  'SELECT 1'
);
PREPARE stmt_mail_config_imap_username FROM @mail_config_imap_username_sql;
EXECUTE stmt_mail_config_imap_username;
DEALLOCATE PREPARE stmt_mail_config_imap_username;

SET @mail_config_imap_auth_secret_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_gmail_config'
    AND column_name = 'imap_auth_secret'
);
SET @mail_config_imap_auth_secret_sql := IF(
  @mail_config_imap_auth_secret_exists = 0,
  'ALTER TABLE `ticket_gmail_config` ADD COLUMN `imap_auth_secret` varchar(1000) DEFAULT NULL COMMENT ''IMAP授权码'' AFTER `imap_username`',
  'SELECT 1'
);
PREPARE stmt_mail_config_imap_auth_secret FROM @mail_config_imap_auth_secret_sql;
EXECUTE stmt_mail_config_imap_auth_secret;
DEALLOCATE PREPARE stmt_mail_config_imap_auth_secret;

SET @mail_config_imap_folder_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_gmail_config'
    AND column_name = 'imap_folder'
);
SET @mail_config_imap_folder_sql := IF(
  @mail_config_imap_folder_exists = 0,
  'ALTER TABLE `ticket_gmail_config` ADD COLUMN `imap_folder` varchar(255) DEFAULT NULL COMMENT ''IMAP文件夹'' AFTER `imap_auth_secret`',
  'SELECT 1'
);
PREPARE stmt_mail_config_imap_folder FROM @mail_config_imap_folder_sql;
EXECUTE stmt_mail_config_imap_folder;
DEALLOCATE PREPARE stmt_mail_config_imap_folder;

SET @mail_record_provider_type_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_mail_record'
    AND column_name = 'provider_type'
);
SET @mail_record_provider_type_sql := IF(
  @mail_record_provider_type_exists = 0,
  'ALTER TABLE `ticket_mail_record` ADD COLUMN `provider_type` varchar(32) DEFAULT ''gmail_api'' COMMENT ''接入类型'' AFTER `gmail_thread_id`',
  'SELECT 1'
);
PREPARE stmt_mail_record_provider_type FROM @mail_record_provider_type_sql;
EXECUTE stmt_mail_record_provider_type;
DEALLOCATE PREPARE stmt_mail_record_provider_type;

UPDATE `ticket_gmail_config`
SET `provider_type` = 'gmail_api'
WHERE `provider_type` IS NULL OR TRIM(`provider_type`) = '';

UPDATE `ticket_mail_record`
SET `provider_type` = 'gmail_api'
WHERE `provider_type` IS NULL OR TRIM(`provider_type`) = '';

UPDATE `sys_menu`
SET `menu_name` = '邮件接入配置', `remark` = '邮件接入配置菜单'
WHERE `menu_id` = 20010;

UPDATE `sys_menu`
SET `menu_name` = '邮件配置', `remark` = '邮件配置权限'
WHERE `menu_id` = 20901;

UPDATE `sys_menu`
SET `menu_name` = '邮件同步', `remark` = '邮件同步权限'
WHERE `menu_id` = 20902;
