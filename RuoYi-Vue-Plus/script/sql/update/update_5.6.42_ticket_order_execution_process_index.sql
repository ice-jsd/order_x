SET @schema_name := DATABASE();

SELECT COUNT(*) INTO @idx_ticket_order_execution_process_status_exists
FROM information_schema.STATISTICS
WHERE table_schema = @schema_name
  AND table_name = 'ticket_order_execution'
  AND index_name = 'idx_ticket_order_execution_process_status';

SET @idx_ticket_order_execution_process_status_sql := IF(
  @idx_ticket_order_execution_process_status_exists = 0,
  'ALTER TABLE `ticket_order_execution` ADD INDEX `idx_ticket_order_execution_process_status` (`task_id`, `schedule_version`, `execution_status`, `execution_id`)',
  'SELECT 1'
);
PREPARE stmt_idx_ticket_order_execution_process_status FROM @idx_ticket_order_execution_process_status_sql;
EXECUTE stmt_idx_ticket_order_execution_process_status;
DEALLOCATE PREPARE stmt_idx_ticket_order_execution_process_status;

SELECT COUNT(*) INTO @idx_ticket_order_execution_process_schedule_exists
FROM information_schema.STATISTICS
WHERE table_schema = @schema_name
  AND table_name = 'ticket_order_execution'
  AND index_name = 'idx_ticket_order_execution_process_schedule';

SET @idx_ticket_order_execution_process_schedule_sql := IF(
  @idx_ticket_order_execution_process_schedule_exists = 0,
  'ALTER TABLE `ticket_order_execution` ADD INDEX `idx_ticket_order_execution_process_schedule` (`task_id`, `schedule_version`, `lottery_schedule_id`, `execution_id`)',
  'SELECT 1'
);
PREPARE stmt_idx_ticket_order_execution_process_schedule FROM @idx_ticket_order_execution_process_schedule_sql;
EXECUTE stmt_idx_ticket_order_execution_process_schedule;
DEALLOCATE PREPARE stmt_idx_ticket_order_execution_process_schedule;
