package com.company.ability.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员列表项（含配置超管合成条目）
 */
@Data
public class AssessAdminVO {

    /** 表记录 ID；配置超管且未在表中时为空 */
    private Long id;

    /** OA 登录名 */
    private String loginid;

    /** 姓名（查泛微人员表，查不到为空） */
    private String lastname;

    /** 备注 */
    private String remark;

    /** 是否配置超管（assess.bootstrap-admins）：不可界面删除 */
    private Boolean bootstrap;

    /** 加入时间（表记录） */
    private LocalDateTime createTime;
}
