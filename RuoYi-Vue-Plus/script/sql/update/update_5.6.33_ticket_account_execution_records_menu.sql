SET NAMES utf8mb4;

-- 账号池执行记录菜单
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20011, '执行记录', 20000, 4, 'registration-batch', 'ticket/registration-batch/index', '', 1, 0, 'C', '0', '0', 'ticket:account:list', 'list', 103, 1, NOW(), NULL, NULL, '账号池批量注册/登录执行记录'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20011);

UPDATE `sys_menu`
SET `visible` = '0', `status` = '0'
WHERE `menu_id` = 20011;

UPDATE `sys_menu`
SET `order_num` = 5
WHERE `menu_id` = 20010 AND `order_num` = 4;

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20011
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20011);
