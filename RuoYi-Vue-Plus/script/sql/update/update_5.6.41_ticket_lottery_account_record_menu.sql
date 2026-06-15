SET NAMES utf8mb4;

-- 账号抽票记录菜单
UPDATE `sys_menu`
SET `menu_name` = '抢购运营',
    `remark` = '抢购运营目录',
    `update_time` = NOW()
WHERE `menu_id` = 20000
   OR (`path` = 'ticket' AND `menu_type` = 'M');

INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT 20012, '账号抽票记录', 20000, 9, 'lottery-account-record', 'ticket/lottery-account-record/index', '', 1, 0, 'C', '0', '0', 'ticket:orderExecution:list', 'list', 103, 1, NOW(), NULL, NULL, '账号抽票记录菜单'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20012)
  AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `path` = 'lottery-account-record' AND `component` = 'ticket/lottery-account-record/index');

UPDATE `sys_menu`
SET `menu_name` = '账号抽票记录',
    `parent_id` = 20000,
    `order_num` = 9,
    `path` = 'lottery-account-record',
    `component` = 'ticket/lottery-account-record/index',
    `menu_type` = 'C',
    `visible` = '0',
    `status` = '0',
    `perms` = 'ticket:orderExecution:list',
    `icon` = 'list',
    `remark` = '账号抽票记录菜单',
    `update_time` = NOW()
WHERE `menu_id` = 20012
   OR (`path` = 'lottery-account-record' AND `component` = 'ticket/lottery-account-record/index');

UPDATE `sys_menu`
SET `order_num` = 10,
    `update_time` = NOW()
WHERE `menu_id` = 20009
  AND `order_num` = 9;

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, 20012
FROM dual
WHERE EXISTS (SELECT 1 FROM `sys_menu` WHERE `menu_id` = 20012)
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` = 20012);
