SET @mail_lottery_application_no_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_mail_record'
    AND column_name = 'lottery_application_no'
);

SET @mail_lottery_application_no_sql := IF(
  @mail_lottery_application_no_exists = 0,
  'ALTER TABLE `ticket_mail_record` ADD COLUMN `lottery_application_no` varchar(64) DEFAULT NULL COMMENT ''LivePocket申込番号'' AFTER `activation_url`',
  'SELECT 1'
);
PREPARE stmt_mail_lottery_application_no FROM @mail_lottery_application_no_sql;
EXECUTE stmt_mail_lottery_application_no;
DEALLOCATE PREPARE stmt_mail_lottery_application_no;

SET @mail_lottery_result_status_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_mail_record'
    AND column_name = 'lottery_result_status'
);

SET @mail_lottery_result_status_sql := IF(
  @mail_lottery_result_status_exists = 0,
  'ALTER TABLE `ticket_mail_record` ADD COLUMN `lottery_result_status` varchar(32) DEFAULT NULL COMMENT ''抽选结果状态'' AFTER `lottery_application_no`',
  'SELECT 1'
);
PREPARE stmt_mail_lottery_result_status FROM @mail_lottery_result_status_sql;
EXECUTE stmt_mail_lottery_result_status;
DEALLOCATE PREPARE stmt_mail_lottery_result_status;

SET @order_lottery_result_status_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_order_execution'
    AND column_name = 'lottery_result_status'
);

SET @order_lottery_result_status_sql := IF(
  @order_lottery_result_status_exists = 0,
  'ALTER TABLE `ticket_order_execution` ADD COLUMN `lottery_result_status` varchar(32) DEFAULT NULL COMMENT ''抽选结果状态'' AFTER `raw_result`',
  'SELECT 1'
);
PREPARE stmt_order_lottery_result_status FROM @order_lottery_result_status_sql;
EXECUTE stmt_order_lottery_result_status;
DEALLOCATE PREPARE stmt_order_lottery_result_status;

SET @order_lottery_mail_record_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_order_execution'
    AND column_name = 'lottery_result_mail_record_id'
);

SET @order_lottery_mail_record_sql := IF(
  @order_lottery_mail_record_exists = 0,
  'ALTER TABLE `ticket_order_execution` ADD COLUMN `lottery_result_mail_record_id` bigint(20) DEFAULT NULL COMMENT ''抽选结果邮件记录ID'' AFTER `lottery_result_status`',
  'SELECT 1'
);
PREPARE stmt_order_lottery_mail_record FROM @order_lottery_mail_record_sql;
EXECUTE stmt_order_lottery_mail_record;
DEALLOCATE PREPARE stmt_order_lottery_mail_record;

SET @order_lottery_result_at_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_order_execution'
    AND column_name = 'lottery_result_at'
);

SET @order_lottery_result_at_sql := IF(
  @order_lottery_result_at_exists = 0,
  'ALTER TABLE `ticket_order_execution` ADD COLUMN `lottery_result_at` datetime DEFAULT NULL COMMENT ''抽选结果时间'' AFTER `lottery_result_mail_record_id`',
  'SELECT 1'
);
PREPARE stmt_order_lottery_result_at FROM @order_lottery_result_at_sql;
EXECUTE stmt_order_lottery_result_at;
DEALLOCATE PREPARE stmt_order_lottery_result_at;

SET @mail_lottery_application_idx_exists := (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_mail_record'
    AND index_name = 'idx_ticket_mail_record_lottery_application_no'
);

SET @mail_lottery_application_idx_sql := IF(
  @mail_lottery_application_idx_exists = 0,
  'ALTER TABLE `ticket_mail_record` ADD INDEX `idx_ticket_mail_record_lottery_application_no` (`lottery_application_no`)',
  'SELECT 1'
);
PREPARE stmt_mail_lottery_application_idx FROM @mail_lottery_application_idx_sql;
EXECUTE stmt_mail_lottery_application_idx;
DEALLOCATE PREPARE stmt_mail_lottery_application_idx;

SET @order_lottery_result_idx_exists := (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ticket_order_execution'
    AND index_name = 'idx_ticket_order_execution_lottery_result'
);

SET @order_lottery_result_idx_sql := IF(
  @order_lottery_result_idx_exists = 0,
  'ALTER TABLE `ticket_order_execution` ADD INDEX `idx_ticket_order_execution_lottery_result` (`purchase_type`, `order_no`, `lottery_result_status`)',
  'SELECT 1'
);
PREPARE stmt_order_lottery_result_idx FROM @order_lottery_result_idx_sql;
EXECUTE stmt_order_lottery_result_idx;
DEALLOCATE PREPARE stmt_order_lottery_result_idx;

UPDATE `ticket_mail_record`
SET `parse_type` = 'lottery_selected',
    `parsed` = 1,
    `lottery_result_status` = 'selected',
    `lottery_application_no` = REGEXP_REPLACE(
      REGEXP_SUBSTR(`body_content`, '申込番号[[:space:]]*[：:][[:space:]]*[0-9]+'),
      '[^0-9]',
      ''
    )
WHERE `del_flag` = 0
  AND (`lottery_application_no` IS NULL OR `lottery_result_status` IS NULL)
  AND `subject` LIKE '%[LivePocket]抽選結果のお知らせ%'
  AND `body_content` LIKE '%ご当選されました%'
  AND REGEXP_LIKE(`body_content`, '申込番号[[:space:]]*[：:][[:space:]]*[0-9]+');

UPDATE `ticket_order_execution` execution
JOIN `ticket_mail_record` record
  ON record.`tenant_id` = execution.`tenant_id`
  AND record.`del_flag` = 0
  AND record.`lottery_result_status` = 'selected'
  AND record.`lottery_application_no` = execution.`order_no`
SET execution.`lottery_result_status` = 'selected',
    execution.`lottery_result_mail_record_id` = record.`record_id`,
    execution.`lottery_result_at` = COALESCE(record.`received_at`, NOW()),
    execution.`result_message` = '抽选已中选'
WHERE execution.`del_flag` = 0
  AND execution.`purchase_type` = 'lottery'
  AND (execution.`lottery_result_status` IS NULL OR execution.`lottery_result_status` <> 'selected');
