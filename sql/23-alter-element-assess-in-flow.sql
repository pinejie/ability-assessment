-- 23 能力要素增加「流程中需评价」字段
-- assess_in_flow：1-需评价（默认），0-不需评价。
-- 不需评价的要素：人员能力要求中仍可选、仍记录要求等级（作为任职标准），但不进入测评页面——
--   同步服务（AssessSheetSyncService）按「不在要求中」处理（页面行 active=0，已有评级保留），
--   重算存储过程 usp_assess_recalc 只数 active=1 行，天然将其排除出标准分与完整性校验，
--   同类别其余需评价要素自动接管其权重份额（n_c 变小），不产生拖分；归档计算（Java）同口径过滤。
-- 联动约束：assess_in_flow=0 时服务端强制 allow_client_assess=0（流程都不进，客户开关无意义）。

IF COL_LENGTH('dbo.uf_ability_element', 'assess_in_flow') IS NULL
    ALTER TABLE dbo.uf_ability_element
        ADD assess_in_flow INT NOT NULL
        CONSTRAINT DF_uf_ability_element_assess_in_flow DEFAULT (1);
GO
