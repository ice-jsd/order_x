SET NAMES utf8mb4;

SELECT COUNT(1) INTO @idx_ticket_mail_record_feed_latest_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ticket_mail_record'
  AND index_name = 'idx_ticket_mail_record_feed_latest';

SET @idx_ticket_mail_record_feed_latest_sql := IF(
  @idx_ticket_mail_record_feed_latest_exists = 0,
  'ALTER TABLE `ticket_mail_record` ADD INDEX `idx_ticket_mail_record_feed_latest` (`tenant_id`, `del_flag`, `mailbox_id`, `received_at`, `record_id`)',
  'SELECT 1'
);

PREPARE stmt_idx_ticket_mail_record_feed_latest FROM @idx_ticket_mail_record_feed_latest_sql;
EXECUTE stmt_idx_ticket_mail_record_feed_latest;
DEALLOCATE PREPARE stmt_idx_ticket_mail_record_feed_latest;

SELECT COUNT(1) INTO @idx_ticket_mail_record_feed_timeline_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ticket_mail_record'
  AND index_name = 'idx_ticket_mail_record_feed_timeline';

SET @idx_ticket_mail_record_feed_timeline_sql := IF(
  @idx_ticket_mail_record_feed_timeline_exists = 0,
  'ALTER TABLE `ticket_mail_record` ADD INDEX `idx_ticket_mail_record_feed_timeline` (`tenant_id`, `del_flag`, `received_at`, `record_id`)',
  'SELECT 1'
);

PREPARE stmt_idx_ticket_mail_record_feed_timeline FROM @idx_ticket_mail_record_feed_timeline_sql;
EXECUTE stmt_idx_ticket_mail_record_feed_timeline;
DEALLOCATE PREPARE stmt_idx_ticket_mail_record_feed_timeline;

SELECT COUNT(1) INTO @idx_ticket_mail_record_feed_parse_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ticket_mail_record'
  AND index_name = 'idx_ticket_mail_record_feed_parse';

SET @idx_ticket_mail_record_feed_parse_sql := IF(
  @idx_ticket_mail_record_feed_parse_exists = 0,
  'ALTER TABLE `ticket_mail_record` ADD INDEX `idx_ticket_mail_record_feed_parse` (`tenant_id`, `del_flag`, `parse_type`, `received_at`, `record_id`)',
  'SELECT 1'
);

PREPARE stmt_idx_ticket_mail_record_feed_parse FROM @idx_ticket_mail_record_feed_parse_sql;
EXECUTE stmt_idx_ticket_mail_record_feed_parse;
DEALLOCATE PREPARE stmt_idx_ticket_mail_record_feed_parse;

SELECT COUNT(1) INTO @idx_ticket_mail_record_feed_account_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ticket_mail_record'
  AND index_name = 'idx_ticket_mail_record_feed_account';

SET @idx_ticket_mail_record_feed_account_sql := IF(
  @idx_ticket_mail_record_feed_account_exists = 0,
  'ALTER TABLE `ticket_mail_record` ADD INDEX `idx_ticket_mail_record_feed_account` (`tenant_id`, `del_flag`, `account_id`, `received_at`, `record_id`)',
  'SELECT 1'
);

PREPARE stmt_idx_ticket_mail_record_feed_account FROM @idx_ticket_mail_record_feed_account_sql;
EXECUTE stmt_idx_ticket_mail_record_feed_account;
DEALLOCATE PREPARE stmt_idx_ticket_mail_record_feed_account;

SELECT COUNT(1) INTO @idx_ticket_mail_record_feed_email_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ticket_mail_record'
  AND index_name = 'idx_ticket_mail_record_feed_email';

SET @idx_ticket_mail_record_feed_email_sql := IF(
  @idx_ticket_mail_record_feed_email_exists = 0,
  'ALTER TABLE `ticket_mail_record` ADD INDEX `idx_ticket_mail_record_feed_email` (`tenant_id`, `del_flag`, `email`, `received_at`, `record_id`)',
  'SELECT 1'
);

PREPARE stmt_idx_ticket_mail_record_feed_email FROM @idx_ticket_mail_record_feed_email_sql;
EXECUTE stmt_idx_ticket_mail_record_feed_email;
DEALLOCATE PREPARE stmt_idx_ticket_mail_record_feed_email;
