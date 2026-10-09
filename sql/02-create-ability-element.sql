-- 能力要素表
CREATE TABLE uf_ability_element (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    element_name NVARCHAR(100) NOT NULL,
    element_code NVARCHAR(50),
    category_id BIGINT NOT NULL,
    description NVARCHAR(500),
    status INT DEFAULT 1,
    sort INT DEFAULT 0,
    create_by BIGINT,
    create_time DATETIME2 DEFAULT GETDATE(),
    update_by BIGINT,
    update_time DATETIME2 DEFAULT GETDATE(),
    deleted INT DEFAULT 0,
    CONSTRAINT fk_ability_element_category FOREIGN KEY (category_id) REFERENCES uf_ability_category(id)
);

-- 添加索引
CREATE INDEX idx_ability_element_category ON uf_ability_element(category_id);
CREATE INDEX idx_ability_element_status ON uf_ability_element(status);
CREATE INDEX idx_ability_element_sort ON uf_ability_element(sort);

-- 插入测试数据
INSERT INTO uf_ability_element (element_name, element_code, category_id, description, status, sort) VALUES
('责任心', 'CORE-001', 1, '对工作认真负责，尽职尽责', 1, 1),
('团队合作', 'CORE-002', 1, '善于与他人协作，共同完成目标', 1, 2),
('专业技能', 'PROF-001', 2, '具备岗位所需的专业知识和技能', 1, 1),
('沟通能力', 'GEN-001', 3, '能够清晰、准确地表达想法', 1, 1);
