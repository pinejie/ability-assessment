package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.ability.context.DimensionContext;
import com.company.ability.entity.PaCategory;
import com.company.ability.entity.PaItem;
import com.company.ability.entity.PaItemLevel;
import com.company.ability.entity.PaPost;
import com.company.ability.entity.PaSheet;
import com.company.ability.entity.PaSheetItem;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.PaCategoryMapper;
import com.company.ability.mapper.PaItemLevelMapper;
import com.company.ability.mapper.PaItemMapper;
import com.company.ability.mapper.PaPostMapper;
import com.company.ability.mapper.PaSheetItemMapper;
import com.company.ability.mapper.PaSheetMapper;
import com.company.ability.service.PaSheetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 测评页装配实现。
 *
 * <p>装配时点只有一个闸门失败会让发起失败（其余在发起侧校验）：</p>
 * <ol>
 *   <li>岗位下没有启用中的能力项 —— 发出去了也是空页面</li>
 * </ol>
 * <p>岗位由发起人在发起页直接选定（方案 17 增补，2026-11-21）：原先要先到
 * 「人员岗位映射」把人和岗位配一遍，纯属多一道手续 —— 那张表只服务于这一个动作。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaSheetServiceImpl implements PaSheetService {

    static final int ERR_NO_MODEL = 40602;
    static final int ERR_PERIOD_NOT_FOUND = 40603;
    /** 页面已封存（sql/64）：重新发起会把分数与行全部清空，与封存快照分叉 */
    static final int ERR_SHEET_SEALED = 40604;

    private final PaSheetMapper sheetMapper;
    private final PaSheetItemMapper sheetItemMapper;
    private final PaPostMapper postMapper;
    private final PaCategoryMapper categoryMapper;
    private final PaItemMapper itemMapper;
    private final PaItemLevelMapper levelMapper;
    private final JdbcTemplate jdbc;

    @Value("${assess.company-id:1}")
    private long companyId;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaSheet prepare(int year, long resourceId, long postId) {
        long dimensionId = DimensionContext.getOrDefault();

        Map<String, Object> period = loadPeriod(year, dimensionId);
        long periodId = num(period.get("id"));

        PaPost post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(ERR_NO_MODEL, "岗位不存在（可能已被删除），请重新选择");
        }

        List<PaItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, post.getId())
                .eq(PaItem::getStatus, 1)
                .orderByAsc(PaItem::getCategoryId).orderByAsc(PaItem::getSort).orderByAsc(PaItem::getId));
        if (items.isEmpty()) {
            throw new BusinessException(ERR_NO_MODEL,
                    "岗位「" + deptPost(post) + "」下还没有启用的能力项，请先在「岗位能力模型」中配置");
        }

        // 一页 = 一人一年。已存在就复用（撤回后重发是同一次评价征集），
        // 报告上因此不会出现同一个人的两份同年报告。
        PaSheet sheet = findSheet(periodId, resourceId);
        // 已封存的页面不能重新发起（sql/64）：下面会清空分数、重建页面行，
        // 而封存快照里存的正是那一刻的分数 —— 放行等于让「已封存」的那份与库里分叉。
        if (sheet != null && sheet.getSealStatus() != null && sheet.getSealStatus() == 1) {
            throw new BusinessException(ERR_SHEET_SEALED,
                    "该人员本年度的报告已封存，请先在测评报告页解除封存再重新发起");
        }
        boolean created = sheet == null;
        if (created) {
            sheet = new PaSheet();
            sheet.setCompanyId(companyId);
            sheet.setDimensionId(dimensionId);
            sheet.setPeriodId(periodId);
            sheet.setResourceId(resourceId);
        }
        sheet.setPostId(post.getId());
        // 部门快照：优先取被测人自己的部门（sql/63 起）。
        //   · 普通岗位：旧逻辑取岗位的部门，而发起时强制「人部门 = 岗位部门」，两者必然相等，
        //     所以换成取人对我存量数据零影响，报告上的部门也不会变。
        //   · 通用岗位：岗位没有部门，只能取人的 —— 不然报告上部门那列是空的。
        // 人查不到部门（理论上发起侧已拦）就退回岗位的部门，尽量不留空。
        Long targetDeptId = departmentOf(resourceId);
        if (targetDeptId != null) {
            sheet.setDepartmentId(targetDeptId);
            sheet.setDepartmentName(departmentMarkOf(targetDeptId));
        } else {
            sheet.setDepartmentId(post.getDepartmentId());
            sheet.setDepartmentName(post.getDepartmentName());
        }
        sheet.setPostName(post.getPostName());
        sheet.setLeaderResourceId(resolveLeader(resourceId));
        sheet.setItemCount(items.size());
        // 重新发起 = 新一轮评价：分数与评语一律清空，否则页面会是「旧分数 + 新评级」的混合体
        sheet.setStatus(0);
        sheet.setSelfScore(null);
        sheet.setLeaderScore(null);
        sheet.setRater360Score(null);
        sheet.setTotalScore(null);
        sheet.setBandName(null);
        sheet.setCommentText(null);
        sheet.setSnapshotJson(null);
        if (created) {
            sheetMapper.insert(sheet);
        } else {
            // 清空字段必须显式写 NULL：MyBatis-Plus 的 updateById 默认策略是 NOT_NULL，
            // setTotalScore(null) 会被静默跳过 —— 重新发起后页面仍挂着上一轮的成绩。
            sheetMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PaSheet>()
                    .eq("id", sheet.getId())
                    .set("post_id", sheet.getPostId())
                    .set("department_id", sheet.getDepartmentId())
                    .set("department_name", sheet.getDepartmentName())
                    .set("post_name", sheet.getPostName())
                    .set("leader_resource_id", sheet.getLeaderResourceId())
                    .set("item_count", sheet.getItemCount())
                    .set("status", sheet.getStatus())
                    .set("self_score", null)
                    .set("leader_score", null)
                    .set("rater360_score", null)
                    .set("total_score", null)
                    .set("band_name", null)
                    .set("comment_text", null)
                    .set("snapshot_json", null)
                    // 新一轮评价从「未封存」开始（正常路径上已封存的页面在第 62 行就被挡住了，
                    // 这里兜的是封存字段上线前遗留的异常组合：有快照但状态是 0）
                    .set("seal_status", 0)
                    // seal_time 是 datetime2，这一列的空值必须用 setSql 写成字面 NULL。
                    // 用 set("seal_time", null) 时 MyBatis 不带 jdbcType，SQL Server 驱动
                    // 按 varbinary 下发，datetime2 列不做这个隐式转换，直接报
                    // 「不允许从数据类型 varbinary 到 datetime2 的隐式转换」——
                    // 表现是「重新发起测评」（复用已有页走 UPDATE）必失败，新建（走 insert
                    // 跳过 null 列）反而正常。同一块的 decimal / nvarchar 空值实测能隐式转换，
                    // 不必一起改；只有 datetime2 这一类要绕开类型推断。
                    .setSql("seal_time = NULL")
                    .set("seal_by_loginid", null)
                    .set("seal_by_name", null)
                    .set("sealed_json", null)
                    .set("update_time", java.time.LocalDateTime.now()));
            sheetItemMapper.delete(new LambdaQueryWrapper<PaSheetItem>()
                    .eq(PaSheetItem::getSheetId, sheet.getId()));
        }

        writeItems(sheet, items);
        log.info("装配测评页 sheetId={}, year={}, resourceId={}, post={}, 能力项={}{}",
                sheet.getId(), year, resourceId, post.getPostName(), items.size(),
                created ? "（新建）" : "（复用并重建行）");
        return sheetMapper.selectById(sheet.getId());
    }

    // ==================== 私有 ====================

    /** 把岗位的能力项快照成页面行：名称/类别名/权重/评级主体/定义全部落定，之后改岗位不影响本页 */
    private void writeItems(PaSheet sheet, List<PaItem> items) {
        Map<Long, String> categoryNames = categoryMapper.selectList(new LambdaQueryWrapper<PaCategory>()
                        .eq(PaCategory::getPostId, sheet.getPostId()))
                .stream().collect(Collectors.toMap(PaCategory::getId, PaCategory::getCategoryName));

        List<PaSheetItem> rows = new ArrayList<>(items.size());
        int sort = 0;
        for (PaItem item : items) {
            PaSheetItem row = new PaSheetItem();
            row.setDimensionId(sheet.getDimensionId());
            row.setSheetId(sheet.getId());
            row.setItemId(item.getId());
            row.setCategoryId(item.getCategoryId());
            row.setCategoryName(categoryNames.get(item.getCategoryId()));
            row.setItemCode(item.getItemCode());
            row.setItemName(item.getItemName());
            row.setDefinition(item.getDefinition());
            row.setSortNo(sort++);
            row.setWeight(item.getWeight());
            row.setRaterLeader(item.getRaterLeader());
            row.setRater360(item.getRater360());
            row.setRaterText(item.getRaterText());
            row.setRater360Count(0);
            // 项得分与刻度名此刻都没有：装配只快照配置，不出分。
            // 两者都由算分（PaScoreServiceImpl）一并写入，这里保持 NULL。
            row.setItemScore(null);
            row.setItemLevelName(null);
            rows.add(row);
        }
        for (PaSheetItem row : rows) {
            sheetItemMapper.insert(row);
        }
    }

    private PaSheet findSheet(long periodId, long resourceId) {
        List<PaSheet> list = sheetMapper.selectList(new LambdaQueryWrapper<PaSheet>()
                .eq(PaSheet::getPeriodId, periodId)
                .eq(PaSheet::getResourceId, resourceId)
                .orderByDesc(PaSheet::getId)
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<String, Object> loadPeriod(int year, long dimensionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status FROM uf_assess_period WHERE company_id = ? AND year = ? AND dimension_id = ?",
                companyId, year, dimensionId);
        if (rows.isEmpty()) {
            throw new BusinessException(ERR_PERIOD_NOT_FOUND, year + " 年度不存在，请先在「年度设置」中新增");
        }
        return rows.get(0);
    }

    /**
     * 直接上级：只认泛微 {@code HrmResource.managerid}（口径 #11，不可人工指定）。
     * 返回 null 时不在这里拦 —— 发起侧有更具体的错误提示（含人名）。
     */
    private Long resolveLeader(long resourceId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT managerid FROM HrmResource WHERE id = ?", Long.class, resourceId);
        if (ids.isEmpty()) {
            return null;
        }
        Long managerId = ids.get(0);
        return managerId == null || managerId == 0L ? null : managerId;
    }

    /** 被测人在 OA 的部门；查不到返回 null（上游发起侧已拦，这里只做兜底） */
    private Long departmentOf(long resourceId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT departmentid FROM HrmResource WHERE id = ?", Long.class, resourceId);
        if (ids.isEmpty() || ids.get(0) == null || ids.get(0) <= 0L) {
            return null;
        }
        return ids.get(0);
    }

    /**
     * 部门名快照。
     *
     * <p>取 {@code departmentmark}（与 {@code PaPostServiceImpl.resolveDepartmentName} 同一字段）：
     * 两处口径必须一致，否则同一个部门在岗位页和报告上显示成两个名字。</p>
     */
    private String departmentMarkOf(long departmentId) {
        List<String> names = jdbc.queryForList(
                "SELECT departmentmark FROM HrmDepartment WHERE id = ?", String.class, departmentId);
        return names.isEmpty() ? null : names.get(0);
    }

    /** 「部门 · 岗位」；通用岗位没有部门，只显示岗位名 */
    private static String deptPost(PaPost post) {
        return post.getDepartmentName() == null || post.getDepartmentName().isBlank()
                ? post.getPostName()
                : post.getDepartmentName() + " · " + post.getPostName();
    }

    private static long num(Object o) {
        return o == null || !(o instanceof Number n) ? 0L : n.longValue();
    }
}
