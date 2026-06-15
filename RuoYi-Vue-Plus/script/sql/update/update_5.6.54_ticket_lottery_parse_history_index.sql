SET NAMES utf8mb4;

SELECT COUNT(1) INTO @idx_ticket_lottery_parse_history_platform_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ticket_lottery_event_parse_record'
  AND index_name = 'idx_ticket_lottery_parse_history_platform';

SET @idx_ticket_lottery_parse_history_platform_sql := IF(
    @idx_ticket_lottery_parse_history_platform_exists = 0,
    'ALTER TABLE `ticket_lottery_event_parse_record` ADD INDEX `idx_ticket_lottery_parse_history_platform` (`tenant_id`, `platform_id`, `parse_status`, `del_flag`, `update_time`, `create_time`)',
    'SELECT 1'
);
PREPARE idx_ticket_lottery_parse_history_platform_stmt FROM @idx_ticket_lottery_parse_history_platform_sql;
EXECUTE idx_ticket_lottery_parse_history_platform_stmt;
DEALLOCATE PREPARE idx_ticket_lottery_parse_history_platform_stmt;

SELECT COUNT(1) INTO @idx_ticket_lottery_parse_history_global_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'ticket_lottery_event_parse_record'
  AND index_name = 'idx_ticket_lottery_parse_history_global';

SET @idx_ticket_lottery_parse_history_global_sql := IF(
    @idx_ticket_lottery_parse_history_global_exists = 0,
    'ALTER TABLE `ticket_lottery_event_parse_record` ADD INDEX `idx_ticket_lottery_parse_history_global` (`tenant_id`, `parse_status`, `del_flag`, `update_time`, `create_time`)',
    'SELECT 1'
);
PREPARE idx_ticket_lottery_parse_history_global_stmt FROM @idx_ticket_lottery_parse_history_global_sql;
EXECUTE idx_ticket_lottery_parse_history_global_stmt;
DEALLOCATE PREPARE idx_ticket_lottery_parse_history_global_stmt;
