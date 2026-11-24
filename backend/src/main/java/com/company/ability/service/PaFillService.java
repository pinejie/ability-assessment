package com.company.ability.service;

import com.company.ability.dto.PaFillDTO;
import com.company.ability.vo.PaFillPageVO;
import com.company.ability.vo.PaTaskVO;

/**
 * 评价填写（方案 17 §7.2）。
 *
 * <p>三条铁律：</p>
 * <ol>
 *   <li>单人提交只写评级、不算分 —— 出分只发生在「全体提交完毕」那一刻（口径 #5）。</li>
 *   <li>防代填靠「登录人 == 评价人」，token 只是入口不是身份；链接被转发也提交不了。</li>
 *   <li>看得见的能力项必须全评（口径 #4）：前端不给「跳过」，后端也拒收漏项。</li>
 * </ol>
 */
public interface PaFillService {

    /** 填写页数据（任务已撤回时只返回状态，不含能力项与草稿） */
    PaFillPageVO page(String token);

    /** 暂存草稿：只写 draft_json，不写评级、不算分 */
    PaTaskVO.DraftResult saveDraft(String token, PaFillDTO.DraftRequest req);

    /** 提交评价：服务端二次校验完整性；单人提交不触发算分，全员齐备才算 */
    PaTaskVO.SubmitResult submit(String token, PaFillDTO.SubmitRequest req);
}
