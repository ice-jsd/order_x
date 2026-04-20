-- ----------------------------
-- 全量移除邮件接入功能
-- ----------------------------

DELETE FROM `sys_role_menu` WHERE `menu_id` IN (20010, 20901, 20902, 20903);
DELETE FROM `sys_menu` WHERE `menu_id` IN (20901, 20902, 20903, 20010);

SET @mail_alias_index_exists := (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_managed_account'
    AND index_name = 'uk_ticket_account_mail_alias'
);
SET @mail_alias_index_sql := IF(
  @mail_alias_index_exists > 0,
  'ALTER TABLE `ticket_managed_account` DROP INDEX `uk_ticket_account_mail_alias`',
  'SELECT 1'
);
PREPARE stmt_drop_mail_alias_index FROM @mail_alias_index_sql;
EXECUTE stmt_drop_mail_alias_index;
DEALLOCATE PREPARE stmt_drop_mail_alias_index;

SET @mail_alias_column_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_managed_account'
    AND column_name = 'mail_alias'
);
SET @mail_alias_column_sql := IF(
  @mail_alias_column_exists > 0,
  'ALTER TABLE `ticket_managed_account` DROP COLUMN `mail_alias`',
  'SELECT 1'
);
PREPARE stmt_drop_mail_alias_column FROM @mail_alias_column_sql;
EXECUTE stmt_drop_mail_alias_column;
DEALLOCATE PREPARE stmt_drop_mail_alias_column;

DROP TABLE IF EXISTS `ticket_mail_record`;
DROP TABLE IF EXISTS `ticket_gmail_config`;
