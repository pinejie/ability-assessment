package com.company.ability.service;

import com.company.ability.dto.AssessArchiveDTO;
import com.company.ability.vo.AssessSubjectVO;

/**
 * 测评归档服务：计算得分、生成快照、写入测评结果
 */
public interface AssessArchiveService {

    /** 归档（幂等：同一流程重复调用返回已有结果） */
    AssessSubjectVO archive(AssessArchiveDTO.ArchiveRequest request);

    /** 查询测评结果详情 */
    AssessSubjectVO getSubjectDetail(Long subjectId);
}
