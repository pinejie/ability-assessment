package com.company.ability.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件总览：模型只看一遍「每张 sheet 抬头两行」，回答「哪些 sheet 是岗位模型、叫什么岗位」。
 *
 * <p><b>为什么要有这一步</b>：客户给的这份 Excel 实测有 25 个 sheet（中层管理、班组长、
 * 销售业务员…一直到审计专员）。一次性让模型把 25 张表的列映射全吐出来，输出会被
 * 思考过程吃穿（实测 max_tokens=16384 仍然截断）。所以拆两步：</p>
 * <ol>
 *   <li>总览（本类）：输入只有 sheet 名 + 每张前两行，输出极小，一次调用搞定</li>
 *   <li>逐张识别（{@link PaImportStructure}）：只对用户勾选的 sheet 做完整列映射</li>
 * </ol>
 *
 * <p>顺带解决了另一个问题：<b>不能让 25 个 sheet 默默变成 25 个岗位</b>。
 * 总览结果是确认页的第一屏，用户勾哪些就导哪些。</p>
 */
@Data
public class PaImportOverview {

    private List<SheetView> sheets = new ArrayList<>();

    private List<String> notes = new ArrayList<>();

    @Data
    public static class SheetView {
        private String sheetName;

        /** IMPORT / IGNORE —— 模型判断这张表是不是岗位能力模型 */
        private String action = "IMPORT";

        /** 模型认出的岗位名（可能是 sheet 名，也可能来自表内标题行） */
        private String postName;

        /** 模型给这一页的一句话说明（为什么判它是 / 不是岗位模型） */
        private String reason;

        /**
         * 代码填的：这张 sheet 的数据规模，供用户在确认页判断。
         * 模型不参与，避免它算错行数。
         */
        private int rowCount;

        private int columnCount;

        /** 代码填的：表头行的原文预览，确认页展示用 */
        private List<String> headerPreview = new ArrayList<>();
    }
}
