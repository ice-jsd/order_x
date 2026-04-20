-- 抢购任务模型重构补迁移：从旧 product/order_flow 模型过渡到 purchase_type/config_snapshot 模型

ALTER TABLE `ticket_sale_task`
  ADD COLUMN `purchase_type` varchar(32) DEFAULT 'flash_sale' COMMENT '抢购类型' AFTER `schedule_version`,
  ADD COLUMN `config_schema_key` varchar(128) DEFAULT NULL COMMENT '配置模板标识' AFTER `purchase_type`;

UPDATE `ticket_sale_task`
SET `purchase_type` = COALESCE(NULLIF(`purchase_type`, ''), 'flash_sale'),
    `config_schema_key` = NULLIF(`config_schema_key`, '');

ALTER TABLE `ticket_sale_task`
  ADD KEY `idx_ticket_sale_task_purchase_type` (`purchase_type`);

ALTER TABLE `ticket_order_execution`
  ADD COLUMN `purchase_type` varchar(32) DEFAULT NULL COMMENT '抢购类型' AFTER `account_id`,
  ADD COLUMN `config_snapshot` longtext COMMENT '配置快照(JSON)' AFTER `purchase_quantity`;

UPDATE `ticket_order_execution` e
LEFT JOIN `ticket_sale_task` t ON t.`task_id` = e.`task_id`
SET e.`purchase_type` = COALESCE(NULLIF(e.`purchase_type`, ''), NULLIF(t.`purchase_type`, ''), 'flash_sale'),
    e.`config_snapshot` = CASE
      WHEN e.`config_snapshot` IS NULL OR e.`config_snapshot` = '' THEN COALESCE(NULLIF(t.`task_options`, ''), '{}')
      ELSE e.`config_snapshot`
    END;
