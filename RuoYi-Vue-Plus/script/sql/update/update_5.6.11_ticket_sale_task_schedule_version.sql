-- 商品抢购任务保存即排队改造：增加调度版本号

ALTER TABLE `ticket_sale_task`
  ADD COLUMN `schedule_version` bigint(20) NOT NULL DEFAULT 1 COMMENT '调度版本号' AFTER `task_status`;

ALTER TABLE `ticket_order_execution`
  ADD COLUMN `schedule_version` bigint(20) NOT NULL DEFAULT 1 COMMENT '调度版本号' AFTER `payment_mode`;

UPDATE `ticket_sale_task`
SET `schedule_version` = 1
WHERE `schedule_version` IS NULL OR `schedule_version` <= 0;

UPDATE `ticket_order_execution` e
LEFT JOIN `ticket_sale_task` t ON t.`task_id` = e.`task_id`
SET e.`schedule_version` = COALESCE(NULLIF(t.`schedule_version`, 0), 1)
WHERE e.`schedule_version` IS NULL OR e.`schedule_version` <= 0;

ALTER TABLE `ticket_order_execution`
  ADD KEY `idx_ticket_order_execution_task_version` (`task_id`, `schedule_version`);
