-- 能力测评配置：能力类型权重、测评对象权重、等级标准

-- 1. 能力类型权重（粒度：能力类别）
CREATE TABLE uf_assess_weight_ability (
    id           BIGINT IDENTITY(1,1) PRIMARY KEY,
    company_id   BIGINT NOT NULL,
    category_id  BIGINT NOT NULL,
    weight       DECIMAL(5,2) NULL,           -- NULL 表示未填写
    update_by    BIGINT NULL,
    update_time  DATETIME2 NOT NULL DEFAULT GETDATE(),
    CONSTRAINT uk_assess_weight_ability UNIQUE (company_id, category_id)
);

-- 2. 测评对象权重（代码固定：LEADER / CLIENT / SELF）
CREATE TABLE uf_assess_weight_assessor (
    id            BIGINT IDENTITY(1,1) PRIMARY KEY,
    company_id    BIGINT NOT NULL,
    assessor_code VARCHAR(20) NOT NULL,
    weight        DECIMAL(5,2) NOT NULL,
    update_by     BIGINT NULL,
    update_time   DATETIME2 NOT NULL DEFAULT GETDATE(),
    CONSTRAINT uk_assess_weight_assessor UNIQUE (company_id, assessor_code)
);

-- 3. 等级标准（区间为左开右闭 (lower_bound, upper_bound]，NULL 表示无穷）
CREATE TABLE uf_assess_grade_level (
    id           BIGINT IDENTITY(1,1) PRIMARY KEY,
    company_id   BIGINT NOT NULL,
    level_name   NVARCHAR(20) NOT NULL,
    lower_bound  DECIMAL(5,2) NULL,
    upper_bound  DECIMAL(5,2) NULL,
    sort_order   INT NOT NULL,
    update_by    BIGINT NULL,
    update_time  DATETIME2 NOT NULL DEFAULT GETDATE()
);
CREATE INDEX idx_assess_grade_company ON uf_assess_grade_level(company_id, sort_order);
