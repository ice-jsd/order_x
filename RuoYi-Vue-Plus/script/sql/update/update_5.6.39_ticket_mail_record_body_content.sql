SET @body_content_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_mail_record'
    AND column_name = 'body_content'
);

SET @body_content_sql := IF(
  @body_content_exists = 0,
  'ALTER TABLE `ticket_mail_record` ADD COLUMN `body_content` longtext COMMENT ''邮件正文'' AFTER `body_excerpt`',
  'SELECT 1'
);
PREPARE stmt_body_content FROM @body_content_sql;
EXECUTE stmt_body_content;
DEALLOCATE PREPARE stmt_body_content;
