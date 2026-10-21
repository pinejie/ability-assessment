-- 16 能力要素增加「是否允许客户评价」字段
-- allow_client_assess：1-允许（默认），0-不允许。
-- 不允许的要素：客户评分按 0 计入加权总分（做法 B），见 17 号存储过程与后端归档计算。

IF COL_LENGTH('dbo.uf_ability_element', 'allow_client_assess') IS NULL
    ALTER TABLE dbo.uf_ability_element
        ADD allow_client_assess INT NOT NULL
        CONSTRAINT DF_uf_ability_element_allow_client DEFAULT (1);
GO
