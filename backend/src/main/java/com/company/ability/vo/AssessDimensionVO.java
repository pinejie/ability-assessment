package com.company.ability.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 测评维度VO
 */
@Data
public class AssessDimensionVO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 维度名称
     */
    private String dimensionName;

    /**
     * 维度编码
     */
    private String dimensionCode;

    /**
     * 描述
     */
    private String description;

    /**
     * 状态：1-启用，0-禁用
     */
    private Integer status;

    /**
     * 排序号
     */
    private Integer sort;

    /**
     * 授权分部（sql/58 合并）：既是「这个维度归属哪几家」，也是「这些分部的人能用」。
     * 列表页直接展示分部名，免得前端为每个维度再查一次分部表。
     */
    private List<AuthItem> subcompanys;

    /**
     * 排除分部（sql/59）：在上面的范围之内、但不归本维度的下级分支（含其下级）。
     * 页面展示成「国际贸易（不含 中达科技）」。
     */
    private List<AuthItem> excludedSubcompanys;

    /**
     * 授权人员（sql/58 合并）：这些人能用这个维度，同时是维度管理员 ——
     * 能在测评报告里看到 360 测评人的真实姓名。
     */
    private List<AuthItem> persons;

    /**
     * 授权条目数（= 分部数 + 人员数）。0 表示只有系统管理员能进
     */
    private Long authCount;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /** 授权条目：对象 ID + 名称（名称查不到时为 null，页面回退显示 ID） */
    @Data
    public static class AuthItem {
        private Long id;
        private String name;
    }
}
