SET NAMES utf8mb4;

-- 票务菜单乱码回正：可重复执行
UPDATE `sys_menu`
SET `menu_name` = '抢购运营',
    `remark` = '抢购运营目录',
    `update_time` = NOW()
WHERE `menu_id` = 20000;

UPDATE `sys_menu`
SET `menu_name` = '邮件总览',
    `remark` = '手机优先邮件总览页',
    `update_time` = NOW()
WHERE `menu_id` = 20014;

UPDATE `sys_menu`
SET `menu_name` = '邮件转发记录',
    `remark` = '邮件转发记录页',
    `update_time` = NOW()
WHERE `menu_id` = 20015;

UPDATE `sys_menu`
SET `menu_name` = '批量抽票任务',
    `remark` = 'LivePocket 批量抽票任务',
    `update_time` = NOW()
WHERE `menu_id` = 20013;

UPDATE `sys_menu`
SET `menu_name` = 'Jump Shop 资料',
    `remark` = 'Jump Shop 结算资料页',
    `update_time` = NOW()
WHERE `menu_id` = 20016;

UPDATE `sys_menu`
SET `menu_name` = '邮件转发发送',
    `update_time` = NOW()
WHERE `menu_id` = 20905;

UPDATE `sys_menu`
SET `menu_name` = '邮件总览导出',
    `remark` = '邮件总览导出按钮权限',
    `update_time` = NOW()
WHERE `menu_id` = 20906;

UPDATE `sys_menu`
SET `menu_name` = '批量抽票查询',
    `remark` = '批量抽票任务查询权限',
    `update_time` = NOW()
WHERE `menu_id` = 21011;

UPDATE `sys_menu`
SET `menu_name` = '批量抽票新增',
    `remark` = '批量抽票任务新增权限',
    `update_time` = NOW()
WHERE `menu_id` = 21012;

UPDATE `sys_menu`
SET `menu_name` = '批量抽票修改',
    `remark` = '批量抽票任务修改权限',
    `update_time` = NOW()
WHERE `menu_id` = 21013;

UPDATE `sys_menu`
SET `menu_name` = '批量抽票执行',
    `remark` = '批量抽票任务执行权限',
    `update_time` = NOW()
WHERE `menu_id` = 21014;

UPDATE `sys_menu`
SET `menu_name` = 'Jump Shop 资料查询',
    `update_time` = NOW()
WHERE `menu_id` = 20910;

UPDATE `sys_menu`
SET `menu_name` = 'Jump Shop 资料新增',
    `update_time` = NOW()
WHERE `menu_id` = 20911;

UPDATE `sys_menu`
SET `menu_name` = 'Jump Shop 资料编辑',
    `update_time` = NOW()
WHERE `menu_id` = 20912;

UPDATE `sys_menu`
SET `menu_name` = 'Jump Shop 资料删除',
    `update_time` = NOW()
WHERE `menu_id` = 20913;
