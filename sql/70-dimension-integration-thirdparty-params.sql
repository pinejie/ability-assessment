-- ============================================================
-- 70 维度集成：把「第三方提供的信息」补成可配
-- ------------------------------------------------------------
-- 背景：66 建表时把接口地址做成了「base URL 推导 + 显式地址覆盖」两级，
-- 但其它东西还按泛微的约定写死在 WeaverProvider 里。配第二个认证方时
-- 会一处处撞墙，而且撞墙点都不在地址上：
--
--   · 回调参数名    泛微回 ticket，标准 OAuth2 回 code —— 前端写死读 ticket，
--                   换一家连票据都取不到，表现是「回调地址缺少 ticket 参数」。
--   · 请求方式      泛微的 accessToken / profile 都是 GET；不少认证方要求 POST，
--                   照现在写死 GET 过去是 405。
--   · 取码字段名    泛微叫 access_token，别家可能叫 token / data.accessToken。
--
-- 这次只补这四项「换一家就必坏」的，不做参数名 DSL：
--   现有适配器只有 WeaverProvider 一个实现，把 query 参数名、成功判定、
--   错误码映射都做成配置项，等于自研一个低代码 OAuth2 客户端，
--   却没有第二个真实认证方来验证这套 schema —— 结果是「看起来什么都能配、
--   真接进来还得改代码」的假通用，比写死更难维护。等真有第二家，对着它的
--   文档配一遍，该配什么自然就清楚了。
--
-- 为什么全部允许 NULL 而不给 DEFAULT 值：
--   NULL 表示「走适配器默认」，与 66 里 base_url / 三个显式地址的语义一致。
--   给 DEFAULT 'ticket' 之类的值，存量行会被灌上一个看起来像「用户明确配过的」
--   值，日后想改泛微默认值时还得先分辨哪些是用户填的、哪些是当年灌的。
--   NULL 就是「没配过」，没有歧义，存量三行一行都不用动。
--
-- 幂等：逐列判断，已存在则跳过（中途失败重跑不会重复加列）。
-- 回滚：
--   ALTER TABLE dbo.uf_assess_dimension_integration DROP CONSTRAINT CK_di_token_method;
--   ALTER TABLE dbo.uf_assess_dimension_integration DROP CONSTRAINT CK_di_profile_method;
--   ALTER TABLE dbo.uf_assess_dimension_integration DROP COLUMN
--       sso_callback_param, sso_token_method, sso_profile_method, sso_access_token_field;
-- ============================================================

USE ecology;
GO

-- 回调 URL 里携带票据的参数名（泛微 ticket / 标准 OAuth2 code）
IF COL_LENGTH('dbo.uf_assess_dimension_integration', 'sso_callback_param') IS NULL
BEGIN
    ALTER TABLE dbo.uf_assess_dimension_integration
        ADD sso_callback_param VARCHAR(32) NULL;
    PRINT '已加列 sso_callback_param';
END
ELSE
    PRINT 'sso_callback_param 已存在，跳过';
GO

-- 票据兑换（accessToken）的请求方式：GET / POST
IF COL_LENGTH('dbo.uf_assess_dimension_integration', 'sso_token_method') IS NULL
BEGIN
    ALTER TABLE dbo.uf_assess_dimension_integration
        ADD sso_token_method VARCHAR(8) NULL;
    PRINT '已加列 sso_token_method';
END
ELSE
    PRINT 'sso_token_method 已存在，跳过';
GO

-- 用户信息（profile）的请求方式：GET / POST
IF COL_LENGTH('dbo.uf_assess_dimension_integration', 'sso_profile_method') IS NULL
BEGIN
    ALTER TABLE dbo.uf_assess_dimension_integration
        ADD sso_profile_method VARCHAR(8) NULL;
    PRINT '已加列 sso_profile_method';
END
ELSE
    PRINT 'sso_profile_method 已存在，跳过';
GO

-- 兑换响应里取票据的字段名（泛微 access_token）
IF COL_LENGTH('dbo.uf_assess_dimension_integration', 'sso_access_token_field') IS NULL
BEGIN
    ALTER TABLE dbo.uf_assess_dimension_integration
        ADD sso_access_token_field VARCHAR(64) NULL;
    PRINT '已加列 sso_access_token_field';
END
ELSE
    PRINT 'sso_access_token_field 已存在，跳过';
GO

-- 请求方式只允许这两个值。配成 post / GET2 这类会让适配器在调用时才发现，
-- 而那时用户看到的是「兑换失败」，看不出是配置写错 —— 从约束层挡住。
-- NULL 不受 CHECK 限制（UNKNOWN 视为通过），所以「没配」仍然合法。
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_di_token_method')
BEGIN
    ALTER TABLE dbo.uf_assess_dimension_integration
        ADD CONSTRAINT CK_di_token_method
        CHECK (sso_token_method IS NULL OR sso_token_method IN ('GET', 'POST'));
    PRINT 'CK_di_token_method 已添加';
END
ELSE
    PRINT 'CK_di_token_method 已存在，跳过';
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_di_profile_method')
BEGIN
    ALTER TABLE dbo.uf_assess_dimension_integration
        ADD CONSTRAINT CK_di_profile_method
        CHECK (sso_profile_method IS NULL OR sso_profile_method IN ('GET', 'POST'));
    PRINT 'CK_di_profile_method 已添加';
END
ELSE
    PRINT 'CK_di_profile_method 已存在，跳过';
GO

PRINT '70 完成：第三方接口参数可配（回调参数名 / 请求方式 / 取码字段）';
GO
