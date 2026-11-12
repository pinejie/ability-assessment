-- ============================================================
-- 35 专业知识 / 专业能力：一次性关闭客户评价
-- ------------------------------------------------------------
-- 背景：
--   业务确认「专业知识」「专业能力」两类要素只由领导与本人评价，不参与客户评价。
--   本次只做存量数据纠正（一次性），不把规则固化进代码：
--   以后新建这两个类别下的要素，仍按表单/导入的表单值决定（默认需客户评价）。
--
-- 口径：
--   assess_in_flow      = 1（需评价）——存量已全部为 1，本脚本只做兜底校正
--   allow_client_assess = 0（不需客户评价）——客户分按 0 计入加权总分（做法 B，
--                         见 sql/30-level-score-dynamic.sql 第 5c 步与
--                         AssessArchiveServiceImpl 的 allowClientAssess 过滤）
--   范围：dimension_id = 3 下的类别「专业知识」(id=27)、「专业能力」(id=28)，
--         仅 deleted = 0 的要素。用类别名 + 维度反查，避免写死 id。
--
-- 影响：
--   uf_ability_element 最多 28 行（1 列）。不改评级表、不改页面行、不删数据。
--   存量草稿测评页面的客户分覆盖范围会随之变化（重算时实时取开关，无需回填）。
--   回滚：uf_ability_element_bak_35 保留改动前的 (id, element_name, assess_in_flow,
--         allow_client_assess)，按 id 回写即可。
--
-- 幂等：以 uf_assess_migration_log 的版本标记为准，重跑不会重复备份/重复更新。
-- ============================================================

IF NOT EXISTS (SELECT 1 FROM dbo.uf_assess_migration_log WHERE version = '35-prof-no-client')
BEGIN
    -- A. 备份将被改动的行
    IF OBJECT_ID('dbo.uf_ability_element_bak_35', 'U') IS NULL
    BEGIN
        SELECT e.id, e.element_name, e.category_id, e.assess_in_flow, e.allow_client_assess,
               SYSDATETIME() AS backup_time
        INTO dbo.uf_ability_element_bak_35
        FROM dbo.uf_ability_element e
        JOIN dbo.uf_ability_category c ON c.id = e.category_id
        WHERE c.dimension_id = 3
          AND c.category_name IN (N'专业知识', N'专业能力')
          AND c.deleted = 0
          AND e.deleted = 0;
    END

    -- B. 关闭客户评价
    UPDATE e
    SET e.allow_client_assess = 0
    FROM dbo.uf_ability_element e
    JOIN dbo.uf_ability_category c ON c.id = e.category_id
    WHERE c.dimension_id = 3
      AND c.category_name IN (N'专业知识', N'专业能力')
      AND c.deleted = 0
      AND e.deleted = 0
      AND e.allow_client_assess <> 0;

    -- C. 兜底：确保这两类要素都需评价
    UPDATE e
    SET e.assess_in_flow = 1
    FROM dbo.uf_ability_element e
    JOIN dbo.uf_ability_category c ON c.id = e.category_id
    WHERE c.dimension_id = 3
      AND c.category_name IN (N'专业知识', N'专业能力')
      AND c.deleted = 0
      AND e.deleted = 0
      AND e.assess_in_flow <> 1;

    -- D. 记录迁移版本
    INSERT INTO dbo.uf_assess_migration_log (version) VALUES ('35-prof-no-client');
END
GO
