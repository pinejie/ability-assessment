-- 21 系统管理员名单（全局角色，不分维度/租户）
-- uf_assess_admin：管理员以泛微 OA 登录名（HrmResource.loginid）为身份锚点。
-- 管理员判定 = 配置超管（assess.bootstrap-admins，不落表）∪ 本表记录。
-- 职责：租户（维度）增删改与授权配置、查看全部租户数据、维护本名单。
-- 小配置表，采用物理删除（无 deleted 字段）。

IF OBJECT_ID('dbo.uf_assess_admin', 'U') IS NULL
CREATE TABLE dbo.uf_assess_admin (
    id          BIGINT IDENTITY(1,1) PRIMARY KEY,
    loginid     NVARCHAR(100) NOT NULL,
    lastname    NVARCHAR(90)  NULL,                    -- HrmResource.lastname 冗余，列表展示
    remark      NVARCHAR(200) NULL,
    create_by   BIGINT NULL,                           -- 操作人 resourceId
    create_time DATETIME2 DEFAULT GETDATE(),
    CONSTRAINT uk_assess_admin_loginid UNIQUE (loginid)
);
GO
