-- ----------------------------
-- 账号池编辑按钮权限
-- ----------------------------

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20302, '账号编辑', 20003, 2, '', '', '', 1, 0, 'F', '0', '0', 'ticket:account:edit', '#', 103, 1, NOW(), NULL, NULL, '账号池编辑按钮'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20302);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20302
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20302);
