-- 等级分值：等级序号(1-5) → 分值，用于测评得分计算

CREATE TABLE uf_assess_level_score (
    id           BIGINT IDENTITY(1,1) PRIMARY KEY,
    company_id   BIGINT NOT NULL,
    level_no     INT NOT NULL,
    score        DECIMAL(5,2) NOT NULL,
    update_by    BIGINT NULL,
    update_time  DATETIME2 NOT NULL DEFAULT GETDATE(),
    CONSTRAINT uk_assess_level_score UNIQUE (company_id, level_no)
);

-- 初始数据（来源：测评报告"等级标准分"表，companyId=1）
INSERT INTO uf_assess_level_score (company_id, level_no, score) VALUES
(1, 1, 2), (1, 2, 3), (1, 3, 5), (1, 4, 7), (1, 5, 10);
