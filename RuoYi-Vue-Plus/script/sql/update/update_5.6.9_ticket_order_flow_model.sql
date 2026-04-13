-- 商品抢购任务多流程建模

ALTER TABLE `ticket_sale_task`
  ADD COLUMN `order_flow_type` varchar(32) DEFAULT 'direct_order' COMMENT '下单方式' AFTER `task_status`,
  ADD COLUMN `fulfillment_type` varchar(32) DEFAULT 'shipping' COMMENT '履约方式' AFTER `order_flow_type`,
  ADD COLUMN `payment_mode` varchar(32) DEFAULT 'pending_manual' COMMENT '支付方式' AFTER `fulfillment_type`,
  ADD COLUMN `task_options` longtext COMMENT '平台扩展参数(JSON)' AFTER `purchase_quantity`;

UPDATE `ticket_sale_task`
SET `order_flow_type` = COALESCE(NULLIF(`order_flow_type`, ''), 'direct_order'),
    `fulfillment_type` = COALESCE(NULLIF(`fulfillment_type`, ''), 'shipping'),
    `payment_mode` = COALESCE(NULLIF(`payment_mode`, ''), 'pending_manual'),
    `task_options` = CASE
      WHEN `task_options` IS NULL OR `task_options` = '' THEN '{}'
      ELSE `task_options`
    END;

ALTER TABLE `ticket_order_execution`
  ADD COLUMN `flow_type` varchar(32) DEFAULT NULL COMMENT '下单方式' AFTER `purchase_quantity`,
  ADD COLUMN `fulfillment_type` varchar(32) DEFAULT NULL COMMENT '履约方式' AFTER `flow_type`,
  ADD COLUMN `payment_mode` varchar(32) DEFAULT NULL COMMENT '支付方式' AFTER `fulfillment_type`,
  ADD COLUMN `current_step` varchar(64) DEFAULT NULL COMMENT '当前步骤' AFTER `payment_mode`,
  ADD COLUMN `step_status` varchar(32) DEFAULT NULL COMMENT '步骤状态' AFTER `current_step`,
  ADD COLUMN `step_trace` longtext COMMENT '步骤轨迹(JSON)' AFTER `step_status`,
  ADD COLUMN `payment_status` varchar(32) DEFAULT NULL COMMENT '支付状态' AFTER `step_trace`;

UPDATE `ticket_order_execution` e
LEFT JOIN `ticket_sale_task` t ON e.`task_id` = t.`task_id`
SET e.`flow_type` = COALESCE(NULLIF(e.`flow_type`, ''), COALESCE(NULLIF(t.`order_flow_type`, ''), 'direct_order')),
    e.`fulfillment_type` = COALESCE(NULLIF(e.`fulfillment_type`, ''), COALESCE(NULLIF(t.`fulfillment_type`, ''), 'shipping')),
    e.`payment_mode` = COALESCE(NULLIF(e.`payment_mode`, ''), COALESCE(NULLIF(t.`payment_mode`, ''), 'pending_manual')),
    e.`current_step` = COALESCE(NULLIF(e.`current_step`, ''), CASE
      WHEN e.`execution_status` = 'queued' THEN 'queued'
      WHEN e.`execution_status` = 'paid' THEN 'completed'
      ELSE 'creating_order'
    END),
    e.`step_status` = COALESCE(NULLIF(e.`step_status`, ''), CASE
      WHEN e.`execution_status` IN ('failed', 'blocked', 'timeout') THEN 'failed'
      WHEN e.`execution_status` = 'queued' THEN 'queued'
      ELSE 'success'
    END),
    e.`step_trace` = CASE
      WHEN e.`step_trace` IS NULL OR e.`step_trace` = '' THEN '[]'
      ELSE e.`step_trace`
    END,
    e.`payment_status` = COALESCE(NULLIF(e.`payment_status`, ''), CASE
      WHEN e.`execution_status` = 'paid' THEN 'paid'
      WHEN COALESCE(NULLIF(t.`payment_mode`, ''), 'pending_manual') = 'online' THEN 'pending_online'
      WHEN COALESCE(NULLIF(t.`payment_mode`, ''), 'pending_manual') = 'cod_store' THEN 'offline_pending'
      ELSE 'manual_pending'
    END);
