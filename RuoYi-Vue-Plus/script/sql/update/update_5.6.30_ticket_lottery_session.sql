-- 抽票时段增加场次信息

SET @schema_name = DATABASE();

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `ticket_sale_task_schedule` ADD COLUMN `session_id` varchar(100) DEFAULT NULL COMMENT ''抽票场次ID'' AFTER `scheduled_time`',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'ticket_sale_task_schedule'
    AND COLUMN_NAME = 'session_id'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE `ticket_sale_task_schedule` ADD COLUMN `session_label` varchar(200) DEFAULT NULL COMMENT ''抽票场次名称'' AFTER `session_id`',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'ticket_sale_task_schedule'
    AND COLUMN_NAME = 'session_label'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
