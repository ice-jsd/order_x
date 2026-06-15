SET NAMES utf8mb4;

-- 抢购平台菜单命名修正
UPDATE `sys_menu`
SET `menu_name` = '抢购平台',
    `remark` = '抢购平台菜单',
    `update_time` = NOW()
WHERE `menu_id` = 20001
   OR (`path` = 'platform' AND `component` = 'ticket/platform/index');
