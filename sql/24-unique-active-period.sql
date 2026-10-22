-- 24 年度唯一性兜底：每公司+维度至多一个「进行中」（status=0）年度
-- 与 2026-10-22 AssessPeriodService 不变式配套（新增/激活自动关闭其他进行中年度）。
-- 筛选唯一索引在库层面兜并发场景：两个会话同时激活不同年度时，第二家 UPDATE 直接撞索引报错回滚。
-- 幂等：索引已存在则跳过；存量若已有同维度多个进行中（当前 ecology 无此状态），先报错交人工处理。

IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'UX_assess_period_active'
                 AND object_id = OBJECT_ID('dbo.uf_assess_period'))
BEGIN
    IF EXISTS (SELECT company_id, dimension_id FROM dbo.uf_assess_period WHERE status = 0
               GROUP BY company_id, dimension_id HAVING COUNT(*) > 1)
        THROW 50120, N'存在同维度多个「进行中」年度记录，请先人工关闭多余年度后再建索引', 1;

    CREATE UNIQUE INDEX UX_assess_period_active
        ON dbo.uf_assess_period (company_id, dimension_id)
        WHERE status = 0;
END
GO
