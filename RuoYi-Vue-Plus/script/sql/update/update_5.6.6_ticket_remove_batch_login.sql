-- ----------------------------
-- 移除批量登录功能入口
-- ----------------------------

UPDATE `ticket_platform_config`
SET `supports_batch_login` = 0,
    `update_time` = NOW()
WHERE `supports_batch_login` <> 0;

DELETE FROM `sys_role_menu`
WHERE `menu_id` IN (20005, 20301);

DELETE FROM `sys_menu`
WHERE `menu_id` IN (20005, 20301);
