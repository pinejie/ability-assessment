-- 岗位能力要求表
CREATE TABLE uf_position_ability_req (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    company_id BIGINT NOT NULL,
    position_id BIGINT NOT NULL,
    element_id BIGINT NOT NULL,
    required_level INT NOT NULL,
    description NVARCHAR(500),
    create_by BIGINT,
    create_time DATETIME2 DEFAULT GETDATE(),
    update_by BIGINT,
    update_time DATETIME2 DEFAULT GETDATE(),
    deleted INT DEFAULT 0,
    CONSTRAINT fk_position_ability_element FOREIGN KEY (element_id) REFERENCES uf_ability_element(id)
);

-- 添加索引
CREATE INDEX idx_position_ability_company ON uf_position_ability_req(company_id);
CREATE INDEX idx_position_ability_position ON uf_position_ability_req(position_id);
CREATE INDEX idx_position_ability_element ON uf_position_ability_req(element_id);

-- 插入测试数据
INSERT INTO uf_position_ability_req (company_id, position_id, element_id, required_level, description) VALUES
(1, 200, 1, 3, '总经理岗位要求：责任心达到高级水平'),
(1, 200, 2, 3, '总经理岗位要求：团队合作达到高级水平'),
(1, 201, 3, 2, '研发工程师岗位要求：专业技能达到中级水平');
