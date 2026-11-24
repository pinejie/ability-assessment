package com.company.ability.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 岗位能力模型整体 VO：一个岗位一整套（类别 + 能力项 + 等级 + 分值区间）。
 *
 * <p>页面是「左树右表 + 区间 Tab」，三块数据互相关联（左树角标要数能力项、
 * 顶部徽标要算权重合计），拆成三个接口会各自闪一次。合成一个接口一次给全。</p>
 */
@Data
public class PaModelVO {

    private Long postId;

    private Long departmentId;

    private String departmentName;

    private String postName;

    /* ---------- 顶部徽标 ---------- */

    /** 权重合计（百分数）：100.00 表示 100% */
    private BigDecimal weightTotal;

    /** 启用中的能力项数 */
    private Integer itemCount;

    /** 启用的能力项里有几项还没配等级（<2 档也算未配好） */
    private Integer unconfiguredItemCount;

    /* ---------- 明细 ---------- */

    private List<PaCategoryVO> categories = new ArrayList<>();

    private List<PaItemVO> items = new ArrayList<>();

    private List<PaBandVO> bands = new ArrayList<>();
}
