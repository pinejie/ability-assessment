package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统管理员（全局角色，不分维度）。
 * 身份锚定泛微 OA 登录名（HrmResource.loginid），与 SSO 登录身份同一锚点。
 * 管理员判定 = 配置超管（assess.bootstrap-admins）∪ 本表记录；配置超管不落表、不可界面删除。
 * 小配置表，删除即物理删除（不配逻辑删除字段）。
 */
@Data
@TableName("uf_assess_admin")
public class AssessAdmin {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * OA 登录名（HrmResource.loginid），唯一
     */
    private String loginid;

    /**
     * 姓名（HrmResource.lastname 冗余，列表展示用）
     */
    private String lastname;

    /**
     * 备注
     */
    private String remark;

    /**
     * 操作人 resourceId（HrmResource.id），添加时写入；配置引导/匿名时为空
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
