-- 商品抢购任务与下单执行改造

ALTER TABLE `ticket_platform_config`
  ADD COLUMN `order_submit_url` varchar(500) DEFAULT NULL COMMENT '下单接口地址' AFTER `callback_url`;

ALTER TABLE `ticket_sale_task`
  ADD COLUMN `product_id` varchar(128) DEFAULT NULL COMMENT '商品ID' AFTER `platform_id`,
  ADD COLUMN `purchase_quantity` int DEFAULT 1 COMMENT '单账号购买数量' AFTER `scheduled_time`;

UPDATE `ticket_sale_task`
SET `product_id` = COALESCE(NULLIF(`product_id`, ''), CONCAT('legacy-', `task_id`))
WHERE `product_id` IS NULL OR `product_id` = '';

UPDATE `ticket_sale_task`
SET `purchase_quantity` = 1
WHERE `purchase_quantity` IS NULL OR `purchase_quantity` <= 0;

ALTER TABLE `ticket_sale_task`
  DROP COLUMN `event_id`,
  DROP COLUMN `task_mode`,
  DROP COLUMN `rule_config`;

ALTER TABLE `ticket_sale_task`
  ADD KEY `idx_ticket_sale_task_product` (`product_id`);

CREATE TABLE IF NOT EXISTS `ticket_sale_task_account` (
  `binding_id` bigint(20) NOT NULL COMMENT '绑定主键',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户编号',
  `task_id` bigint(20) NOT NULL COMMENT '任务主键',
  `account_id` bigint(20) NOT NULL COMMENT '账号主键',
  `create_dept` bigint(20) DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint(20) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` bigint(20) DEFAULT 0 COMMENT '删除标志',
  PRIMARY KEY (`binding_id`),
  UNIQUE KEY `uk_ticket_sale_task_account` (`task_id`, `account_id`, `del_flag`),
  KEY `idx_ticket_sale_task_account_task` (`task_id`),
  KEY `idx_ticket_sale_task_account_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抢购任务账号绑定表';

ALTER TABLE `ticket_order_execution`
  ADD COLUMN `product_id` varchar(128) DEFAULT NULL COMMENT '商品ID' AFTER `account_id`,
  ADD COLUMN `purchase_quantity` int DEFAULT NULL COMMENT '购买数量' AFTER `product_id`,
  ADD COLUMN `raw_result` longtext COMMENT '原始响应摘要' AFTER `result_message`;

UPDATE `ticket_order_execution` e
JOIN `ticket_sale_task` t ON e.`task_id` = t.`task_id`
SET e.`product_id` = t.`product_id`,
    e.`purchase_quantity` = t.`purchase_quantity`
WHERE e.`task_id` IS NOT NULL
  AND (e.`product_id` IS NULL OR e.`purchase_quantity` IS NULL);

DELETE FROM `sys_role_menu` WHERE `menu_id` IN (20006, 20601, 20602, 20603, 20604);
DELETE FROM `sys_menu` WHERE `menu_id` IN (20601, 20602, 20603, 20604, 20006);

UPDATE `sys_menu`
SET `menu_name` = '商品抢购任务', `remark` = '商品抢购任务菜单'
WHERE `menu_id` = 20007;

UPDATE `sys_menu`
SET `menu_name` = '下单执行', `remark` = '下单执行菜单'
WHERE `menu_id` = 20008;

UPDATE `sys_menu`
SET `menu_name` = '任务查询'
WHERE `menu_id` = 20701;

UPDATE `sys_menu`
SET `menu_name` = '任务新增'
WHERE `menu_id` = 20702;

UPDATE `sys_menu`
SET `menu_name` = '任务修改'
WHERE `menu_id` = 20703;

UPDATE `sys_menu`
SET `menu_name` = '任务删除'
WHERE `menu_id` = 20704;

UPDATE `sys_menu`
SET `menu_name` = '任务执行'
WHERE `menu_id` = 20705;

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20802, '执行状态更新', 20008, 2, '', '', '', 1, 0, 'F', '0', '0', 'ticket:orderExecution:edit', '#', 103, 1, SYSDATE(), NULL, NULL, '执行状态更新权限'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20802);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT rm.`role_id`, 20802
FROM `sys_role_menu` rm
WHERE rm.`menu_id` = 20008
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` existing
    WHERE existing.`role_id` = rm.`role_id` AND existing.`menu_id` = 20802
  );
