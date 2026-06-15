SET NAMES utf8mb4;

INSERT INTO `sys_menu`
(
    `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`,
    `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`,
    `create_time`, `update_by`, `update_time`, `remark`
)
SELECT 20014, '邮件总览', 20000, 6, 'mail-overview', 'ticket/mail-overview/index', '', 1, 0, 'C', '0', '0',
       'ticket:mailbox:list', 'mail', 103, 1, NOW(), NULL, NULL, '手机优先邮件总览页'
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20014);

UPDATE `sys_menu`
SET `menu_name` = '邮件总览',
    `parent_id` = 20000,
    `order_num` = 6,
    `path` = 'mail-overview',
    `component` = 'ticket/mail-overview/index',
    `menu_type` = 'C',
    `visible` = '0',
    `status` = '0',
    `perms` = 'ticket:mailbox:list',
    `icon` = 'mail',
    `remark` = '手机优先邮件总览页'
WHERE `menu_id` = 20014;

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20014
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20014)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20014);
