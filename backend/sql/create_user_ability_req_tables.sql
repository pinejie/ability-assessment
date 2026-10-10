-- 人员能力要求主表
CREATE TABLE uf_user_ability_req_new (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    resource_id BIGINT NOT NULL,
    description NVARCHAR(500),
    create_time DATETIME2 DEFAULT GETDATE(),
    update_time DATETIME2 DEFAULT GETDATE()
);

-- 人员能力要求明细表
CREATE TABLE uf_user_ability_req_item (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    req_id BIGINT NOT NULL,
    element_id BIGINT NOT NULL,
    level_id BIGINT,
    score DECIMAL(10,2),
    create_time DATETIME2 DEFAULT GETDATE(),
    CONSTRAINT fk_user_req_item_req FOREIGN KEY (req_id) REFERENCES uf_user_ability_req_new(id)
);

-- 创建索引
CREATE INDEX idx_user_ability_req_resource ON uf_user_ability_req_new(resource_id);
CREATE INDEX idx_user_ability_req_item_req ON uf_user_ability_req_item(req_id);
