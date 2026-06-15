SET NAMES utf8mb4;

SELECT COUNT(1) INTO @ticket_order_execution_lottery_event_url_exists
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ticket_order_execution'
  AND column_name = 'lottery_event_url';

SET @ticket_order_execution_lottery_event_url_sql := IF(
  @ticket_order_execution_lottery_event_url_exists = 0,
  'ALTER TABLE `ticket_order_execution` ADD COLUMN `lottery_event_url` varchar(500) DEFAULT NULL COMMENT ''抽票活动链接'' AFTER `config_snapshot`',
  'SELECT 1'
);

PREPARE stmt_ticket_order_execution_lottery_event_url FROM @ticket_order_execution_lottery_event_url_sql;
EXECUTE stmt_ticket_order_execution_lottery_event_url;
DEALLOCATE PREPARE stmt_ticket_order_execution_lottery_event_url;

UPDATE `ticket_order_execution`
SET `lottery_event_url` = NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`config_snapshot`, '$.eventUrl')), '')
WHERE `purchase_type` = 'lottery'
  AND (`lottery_event_url` IS NULL OR `lottery_event_url` = '')
  AND `config_snapshot` IS NOT NULL
  AND JSON_VALID(`config_snapshot`)
  AND NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`config_snapshot`, '$.eventUrl')), '') IS NOT NULL;

UPDATE `ticket_order_execution`
SET `lottery_event_url` = NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`config_snapshot`, '$.lotteryEventUrl')), '')
WHERE `purchase_type` = 'lottery'
  AND (`lottery_event_url` IS NULL OR `lottery_event_url` = '')
  AND `config_snapshot` IS NOT NULL
  AND JSON_VALID(`config_snapshot`)
  AND NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`config_snapshot`, '$.lotteryEventUrl')), '') IS NOT NULL;

UPDATE `ticket_order_execution`
SET `lottery_event_url` = NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`config_snapshot`, '$.lotteryEntryUrl')), '')
WHERE `purchase_type` = 'lottery'
  AND (`lottery_event_url` IS NULL OR `lottery_event_url` = '')
  AND `config_snapshot` IS NOT NULL
  AND JSON_VALID(`config_snapshot`)
  AND NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`config_snapshot`, '$.lotteryEntryUrl')), '') IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(`config_snapshot`, '$.lotteryEntryUrl')) NOT LIKE '%/receptions/%/tickets%';

SELECT COUNT(1) INTO @idx_ticket_order_execution_lottery_event_account_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ticket_order_execution'
  AND index_name = 'idx_ticket_order_execution_lottery_event_account';

SET @idx_ticket_order_execution_lottery_event_account_sql := IF(
  @idx_ticket_order_execution_lottery_event_account_exists = 0,
  'ALTER TABLE `ticket_order_execution` ADD INDEX `idx_ticket_order_execution_lottery_event_account` (`purchase_type`, `platform_id`, `lottery_event_url`(255), `execution_status`, `account_id`)',
  'SELECT 1'
);

PREPARE stmt_idx_ticket_order_execution_lottery_event_account FROM @idx_ticket_order_execution_lottery_event_account_sql;
EXECUTE stmt_idx_ticket_order_execution_lottery_event_account;
DEALLOCATE PREPARE stmt_idx_ticket_order_execution_lottery_event_account;
