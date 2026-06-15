SET NAMES utf8mb4;

INSERT INTO `sys_menu`
(
    `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`,
    `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`,
    `create_time`, `update_by`, `update_time`, `remark`
)
SELECT 20906, '邮件总览导出', 20014, 2, '', '', '', 1, 0, 'F', '0', '0',
       'ticket:mailbox:export', '#', 103, 1, NOW(), NULL, NULL, '邮件总览导出按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20906);

UPDATE `sys_menu`
SET `menu_name` = '邮件总览导出',
    `parent_id` = 20014,
    `order_num` = 2,
    `menu_type` = 'F',
    `visible` = '0',
    `status` = '0',
    `perms` = 'ticket:mailbox:export',
    `icon` = '#',
    `remark` = '邮件总览导出按钮权限'
WHERE `menu_id` = 20906;

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20906
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20906)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20906);
