-- 人员能力要求表
CREATE TABLE uf_user_ability_req (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    company_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    element_id BIGINT NOT NULL,
    required_level INT NOT NULL,
    description NVARCHAR(500),
    create_by BIGINT,
    create_time DATETIME2 DEFAULT GETDATE(),
    update_by BIGINT,
    update_time DATETIME2 DEFAULT GETDATE(),
    deleted INT DEFAULT 0,
    CONSTRAINT fk_user_ability_element FOREIGN KEY (element_id) REFERENCES uf_ability_element(id)
);

-- 添加索引
CREATE INDEX idx_user_ability_company ON uf_user_ability_req(company_id);
CREATE INDEX idx_user_ability_user ON uf_user_ability_req(user_id);
CREATE INDEX idx_user_ability_element ON uf_user_ability_req(element_id);

-- 插入测试数据
INSERT INTO uf_user_ability_req (company_id, user_id, element_id, required_level, description) VALUES
(1, 1001, 1, 2, '张三：责任心要求中级水平'),
(1, 1001, 2, 2, '张三：团队合作要求中级水平'),
(1, 1002, 3, 3, '李四：专业技能要求高级水平');
