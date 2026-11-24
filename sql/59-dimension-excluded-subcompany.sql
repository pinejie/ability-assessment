-- ============================================================
-- 59 维度授权新增「排除分部」
-- ------------------------------------------------------------
-- 需求（2026-11-23 第三版）：分部授权要按公司层级树来划，同时能在「整棵树可用」的前提下
-- 挖掉某一支。典型场景：
--
--   维度 A 授权给「国际贸易」整棵树
--   但「中达科技」（国际贸易的二级子公司）要单独一个维度 B
--
-- 现有规则只有正向的「勾父含子」：授权了国际贸易，中达科技的人必然命中上级 11，
-- 没有任何减法能做掉它。没有这张排除项，唯一的绕法是手工把国际贸易下面的每个
-- 分支逐个列出来授权 —— 下次组织调整新设一个子公司，就漏一个人进去，且没有提示。
--
-- 实现方式：复用 uf_assess_dimension_auth，新增 auth_type=3（排除分部）。
--   不加新表：排除项的生命周期、整体覆盖保存方式与授权项完全一致，
--   分成两张表后「保存授权」要写两处、删维度要清两处，多出来的都是能忘的地方。
--
-- 语义（写进 AssessDimensionServiceImpl.isAuthorizedFor 的注释）：
--   · 排除含下级：排除中达科技，它下面新设的分支也不归本维度
--   · 人员授权压过排除：手工加进来的人照常可用，否则这条配置没有可信的用法
--
-- 约束要跟着改：uk_dimension_auth 是 (dimension_id, auth_type, auth_id)，
-- auth_type 是它的一部分，所以加一个新的 type 值不需要改约束。
--
-- 无数据变更，存量授权不受影响。
-- 回滚：DELETE FROM uf_assess_dimension_auth WHERE auth_type = 3;
-- ============================================================

USE ecology;
GO

-- 校验：auth_type 没有 CHECK 约束限制取值范围时，这里什么都不用做。
-- 之所以仍保留一个脚本：迁移必须有落盘记录，且下面这条断言能在
-- 「有人给 auth_type 加过 CHECK」的环境里立刻炸出来，而不是等运行时报错。
IF EXISTS (
        SELECT 1 FROM sys.check_constraints c
        JOIN sys.columns col ON col.object_id = c.parent_object_id
        WHERE c.parent_object_id = OBJECT_ID('dbo.uf_assess_dimension_auth')
          AND col.name = 'auth_type'
          AND c.definition NOT LIKE '%3%')
BEGIN
    PRINT N'⚠ uf_assess_dimension_auth.auth_type 上存在 CHECK 约束且未包含 3（排除分部），请先放宽';
    THROW 50001, N'auth_type 的 CHECK 约束不允许 3（排除分部），sql/59 未完成', 1;
END
GO

PRINT N'59-dimension-excluded-subcompany 执行完成：已在授权表中启用 auth_type=3（排除分部）';
GO
