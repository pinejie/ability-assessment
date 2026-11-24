-- ============================================================
-- 63 岗位支持「全公司通用」：department_id 允许为 NULL
-- ------------------------------------------------------------
-- 背景：岗位模型一直是「部门 + 岗位名」，唯一键 (dimension_id, department_id, post_name)。
-- 但现实里有大量跨部门同名的岗位 —— OA 在册数据里「商务经理」出现在 63 个部门、
-- 「项目经理」61 个、「会计助理」9 个、「出纳」8 个、「行政专员」6 个。
-- 这些岗位干的是同一件事，能力模型应该只有一套；按部门各建一份，
-- 改一个能力项要改 N 次，半年后必然各版本漂移。
--
-- 口径：department_id IS NULL = 全公司通用岗位，对所有部门的人都能发起测评。
--   · 不是「通用 + 指定某几个部门」—— 那种需求（只给 3 个部门共用）以后
--     再叠一张 uf_pa_post_scope 即可，本脚本不预埋。
--   · 同名但内容不同的岗位（经理 / 主任 / 科长 / 技术员：销售经理和生产经理
--     差得比同部门的两个专员还大）继续按部门各建各的，不受影响。
--
-- 唯一键不用改：SQL Server 的唯一索引把多个 NULL 视作同一个值，
--   所以 (dimension_id, NULL, '会计') 天然只允许存在一条 —— 通用岗位的同名唯一性
--   自动成立。UX_pa_post_name 原样保留（重建筛选索引会锁表，且没有必要）。
--
-- 存量数据：现有岗位 department_id 全部有值，本脚本不改任何一行，行为完全不变。
--
-- 相关代码改动（同一个需求）：
--   · PaPostServiceImpl  —— 通用岗位不查部门名；唯一性校验对 NULL 部门用 IS NULL
--   · PaTaskServiceImpl.create() —— 部门一致性校验只对「有部门」的岗位生效
--   · PaSheetServiceImpl.prepare() —— 通用岗位下，测评页的部门快照改取被测人自己的部门
--     （原先取岗位的部门；两类岗位在旧逻辑下必然相等，所以对存量零影响）
--
-- 幂等：重复执行不会报错（列已可空时 ALTER 会被跳过）。
-- 回滚：
--   -- 先确认没有通用岗位，否则回滚会因 NOT NULL 冲突失败
--   --   SELECT id, post_name FROM uf_pa_post WHERE department_id IS NULL AND deleted = 0;
--   ALTER TABLE uf_pa_post ALTER COLUMN department_id BIGINT NOT NULL;
-- ============================================================

USE ecology;
GO

IF EXISTS (SELECT 1 FROM sys.columns
           WHERE object_id = OBJECT_ID('dbo.uf_pa_post')
             AND name = 'department_id'
             AND is_nullable = 0)
BEGIN
    ALTER TABLE dbo.uf_pa_post ALTER COLUMN department_id BIGINT NULL;
    PRINT 'uf_pa_post.department_id 已改为可空（NULL = 全公司通用岗位）';
END
ELSE
BEGIN
    PRINT 'uf_pa_post.department_id 已可空，跳过';
END
GO

-- 通用岗位在列表页要能单独筛出来，部门维度又经常按 department_id 查，
-- 现有 IX_pa_post_dim (dimension_id, deleted, status) 覆盖不到「部门为空」的筛选，
-- 但通用岗位总量是几十条量级，全表扫没有性能问题 —— 不额外加索引。
GO

PRINT '63 完成：岗位支持全公司通用（department_id 为 NULL）';
GO
