-- 评分权重配置表
CREATE TABLE uf_score_weight (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    company_id BIGINT NOT NULL,
    weight_name NVARCHAR(100) NOT NULL,
    category_weight DECIMAL(5,2) NOT NULL,
    element_weight DECIMAL(5,2) NOT NULL,
    description NVARCHAR(500),
    create_by BIGINT,
    create_time DATETIME2 DEFAULT GETDATE(),
    update_by BIGINT,
    update_time DATETIME2 DEFAULT GETDATE(),
    deleted INT DEFAULT 0
);

-- 添加索引
CREATE INDEX idx_score_weight_company ON uf_score_weight(company_id);

-- 插入测试数据
INSERT INTO uf_score_weight (company_id, weight_name, category_weight, element_weight, description) VALUES
(1, '标准权重方案', 30.00, 70.00, '类别占30%，要素占70%'),
(1, '均衡权重方案', 50.00, 50.00, '类别和要素各占50%'),
(2, '要素优先方案', 20.00, 80.00, '类别占20%，要素占80%');
