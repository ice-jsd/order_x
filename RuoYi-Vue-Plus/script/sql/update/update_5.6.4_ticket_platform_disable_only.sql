-- 平台接入仅允许禁用，不允许删除

DELETE FROM `sys_role_menu` WHERE `menu_id` = 20104;
DELETE FROM `sys_menu` WHERE `menu_id` = 20104;
