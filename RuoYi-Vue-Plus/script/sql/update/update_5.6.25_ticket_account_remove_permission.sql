-- 账号池批量删除按钮权限
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20303, '账号删除', 20003, 3, '', '', '', 1, 0, 'F', '0', '0', 'ticket:account:remove', '#', 103, 1, NOW(), NULL, NULL, '账号池删除按钮'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20303);
