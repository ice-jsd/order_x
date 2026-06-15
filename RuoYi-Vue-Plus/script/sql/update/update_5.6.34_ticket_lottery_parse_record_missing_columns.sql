-- 修复已部署库缺少抽票活动解析记录异步字段的问题，支持重复执行

SET @schema_name = DATABASE();

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `ticket_lottery_event_parse_record` ADD COLUMN `ticket_entry_url` varchar(500) DEFAULT NULL COMMENT ''LivePocket tickets页链接'' AFTER `event_url`',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'ticket_lottery_event_parse_record'
    AND COLUMN_NAME = 'ticket_entry_url'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `ticket_lottery_event_parse_record` ADD COLUMN `parse_status` varchar(32) DEFAULT ''completed'' COMMENT ''解析状态'' AFTER `raw_summary`',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'ticket_lottery_event_parse_record'
    AND COLUMN_NAME = 'parse_status'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `ticket_lottery_event_parse_record` ADD COLUMN `parse_request_id` varchar(64) DEFAULT NULL COMMENT ''解析请求ID'' AFTER `parse_status`',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'ticket_lottery_event_parse_record'
    AND COLUMN_NAME = 'parse_request_id'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `ticket_lottery_event_parse_record` ADD COLUMN `parse_message` varchar(500) DEFAULT NULL COMMENT ''解析消息'' AFTER `parse_request_id`',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'ticket_lottery_event_parse_record'
    AND COLUMN_NAME = 'parse_message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
