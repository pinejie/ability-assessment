-- 能力类别表
CREATE TABLE uf_ability_category (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    category_name NVARCHAR(100) NOT NULL,
    description NVARCHAR(500),
    status INT DEFAULT 1,
    sort INT DEFAULT 0,
    create_by BIGINT,
    create_time DATETIME2 DEFAULT GETDATE(),
    update_by BIGINT,
    update_time DATETIME2 DEFAULT GETDATE(),
    deleted INT DEFAULT 0
);

-- 添加索引
CREATE INDEX idx_ability_category_status ON uf_ability_category(status);
CREATE INDEX idx_ability_category_sort ON uf_ability_category(sort);

-- 插入测试数据
INSERT INTO uf_ability_category (category_name, description, status, sort) VALUES
('核心能力', '公司核心价值观相关的能力素质', 1, 1),
('专业能力', '岗位所需的专业技能能力', 1, 2),
('通用能力', '适用于所有岗位的基础能力', 1, 3);
