-- 46 人员能力要求唯一键补 dimension_id
-- 背景：UQ_user_ability_req_new_res_year 原为 (resource_id, assess_year)，维度隔离后
--   同一个人同一年度只能存一个维度的要求，第二个维度配置时会被 2627 拦截
--   （同 sql/19 修的三张表是同一类问题）。
-- 说明：索引名保持不变，仍是普通唯一索引（非约束），sql/11 的 IF NOT EXISTS 判断继续成立。
--   列序保持 resource_id, assess_year 前缀不变，既有按这两列出头的查询仍可走索引。
-- 本脚本幂等：先判断当前是否已含 dimension_id，含则跳过。

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UQ_user_ability_req_new_res_year'
           AND object_id = OBJECT_ID('dbo.uf_user_ability_req_new'))
   AND NOT EXISTS (
       SELECT 1
       FROM sys.index_columns ic
       JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
       WHERE ic.object_id = OBJECT_ID('dbo.uf_user_ability_req_new')
         AND ic.index_id = (SELECT index_id FROM sys.indexes
                            WHERE object_id = OBJECT_ID('dbo.uf_user_ability_req_new')
                              AND name = 'UQ_user_ability_req_new_res_year')
         AND c.name = 'dimension_id')
BEGIN
    DROP INDEX UQ_user_ability_req_new_res_year ON dbo.uf_user_ability_req_new;
    PRINT '已删除旧唯一索引 UQ_user_ability_req_new_res_year (resource_id, assess_year)';
END
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.index_columns ic
    JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
    WHERE ic.object_id = OBJECT_ID('dbo.uf_user_ability_req_new')
      AND ic.index_id = (SELECT index_id FROM sys.indexes
                         WHERE object_id = OBJECT_ID('dbo.uf_user_ability_req_new')
                           AND name = 'UQ_user_ability_req_new_res_year')
      AND c.name = 'dimension_id')
    CREATE UNIQUE INDEX UQ_user_ability_req_new_res_year
        ON dbo.uf_user_ability_req_new (resource_id, assess_year, dimension_id);
GO
