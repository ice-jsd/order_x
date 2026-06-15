SET NAMES utf8mb4;

INSERT INTO `sys_menu`
(
    `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`,
    `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`,
    `create_time`, `update_by`, `update_time`, `remark`
)
SELECT 20907, '邮件记录删除', 20014, 3, '', '', '', 1, 0, 'F', '0', '0',
       'ticket:mailRecord:remove', '#', 103, 1, NOW(), NULL, NULL, '邮件总览邮件记录删除按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20907);

UPDATE `sys_menu`
SET `menu_name` = '邮件记录删除',
    `parent_id` = 20014,
    `order_num` = 3,
    `menu_type` = 'F',
    `visible` = '0',
    `status` = '0',
    `perms` = 'ticket:mailRecord:remove',
    `icon` = '#',
    `remark` = '邮件总览邮件记录删除按钮权限'
WHERE `menu_id` = 20907;

INSERT INTO `sys_menu`
(
    `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`,
    `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`,
    `create_time`, `update_by`, `update_time`, `remark`
)
SELECT 20803, '订单记录删除', 20008, 3, '', '', '', 1, 0, 'F', '0', '0',
       'ticket:orderExecution:remove', '#', 103, 1, NOW(), NULL, NULL, '订单列表订单记录删除按钮权限'
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20803);

UPDATE `sys_menu`
SET `menu_name` = '订单记录删除',
    `parent_id` = 20008,
    `order_num` = 3,
    `menu_type` = 'F',
    `visible` = '0',
    `status` = '0',
    `perms` = 'ticket:orderExecution:remove',
    `icon` = '#',
    `remark` = '订单列表订单记录删除按钮权限'
WHERE `menu_id` = 20803;

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20907
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20907)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20907);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20803
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20803)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20803);
