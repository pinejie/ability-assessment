-- ============================================================
-- 69 sso_client_secret 允许为空
-- ------------------------------------------------------------
-- 背景：66 建表时把 sso_client_secret 定为 NOT NULL，当时的假设是
-- 「配了集成就一定有 client_secret」。这个假设在配置页上线后被打破：
--
--   「停用单点登录」是一个合法状态。停用之后用户自然想把这个维度上
--   配错的密钥清掉（留着等于一个用不上的口令放在库里）。但清空写的是
--   NULL，撞上 NOT NULL 约束，保存直接报「不能将 NULL 插入列」。
--   于是用户只有两条路：把错密钥继续留着，或者干脆不保存。
--
-- 为什么是改约束而不是「清空时写空串」：
--   空串和 NULL 在「这个维度到底配没配密钥」这个判断上会打架 ——
--   Service 里所有判空都写的是 ``== null || isBlank()``，两者都被当「没有」，
--   但 ``MERGE`` 之前有一层 ``blankToNull``，最终落库还是 NULL。
--   与其在两层之间来回避，不如让数据库直接接受 NULL 这一种表示。
--
-- 与 sso_enabled 的关系现在是这样：
--   sso_enabled=1 → 必须有密钥（Service 层校验，拦在写库之前）
--   sso_enabled=0 → 密钥可以为 NULL
--   这条规则只在代码里，数据库层面没有 CHECK —— 因为 CHECK 跨列判断
--   会让「先停用、后清密钥」这个两次操作的顺序变得容易踩雷，
--   而两个字段都是超管手工改的，不需要数据库再兜一层。
--
-- 幂等：列已可空时跳过。
-- 回滚：
--   UPDATE dbo.uf_assess_dimension_integration SET sso_client_secret = '' WHERE sso_client_secret IS NULL;
--   ALTER TABLE dbo.uf_assess_dimension_integration ALTER COLUMN sso_client_secret VARCHAR(512) NOT NULL;
-- ============================================================

USE ecology;
GO

IF EXISTS (SELECT 1 FROM sys.columns
           WHERE object_id = OBJECT_ID('dbo.uf_assess_dimension_integration')
             AND name = 'sso_client_secret'
             AND is_nullable = 0)
BEGIN
    ALTER TABLE dbo.uf_assess_dimension_integration
        ALTER COLUMN sso_client_secret VARCHAR(512) NULL;
    PRINT 'sso_client_secret 已改为允许 NULL';
END
ELSE
    PRINT 'sso_client_secret 已允许 NULL，跳过';
GO

PRINT '69 完成：停用登录时可以清空 client_secret';
