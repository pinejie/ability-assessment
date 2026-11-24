package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.PaPostCreateDTO;
import com.company.ability.dto.PaPostUpdateDTO;
import com.company.ability.entity.PaCategory;
import com.company.ability.entity.PaItem;
import com.company.ability.entity.PaItemLevel;
import com.company.ability.entity.PaSheet;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.PaCategoryMapper;
import com.company.ability.mapper.PaItemLevelMapper;
import com.company.ability.mapper.PaItemMapper;
import com.company.ability.mapper.PaSheetMapper;
import com.company.ability.vo.PaPostVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测评岗位服务测试（方案 17 §4.1）：
 * 同部门内岗位名唯一、部门名快照、权重合计随能力项实时汇总、有引用时禁止删除。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("测评岗位服务测试")
class PaPostServiceTest {

    private static final long DIM = 1L;
    private static final long DEPT_HR = 501L;
    private static final long DEPT_FIN = 502L;

    @Autowired
    private PaPostService postService;

    @Autowired
    private PaCategoryMapper categoryMapper;

    @Autowired
    private PaItemMapper itemMapper;

    @Autowired
    private PaItemLevelMapper levelMapper;

    /** 详情页的 unconfiguredItemCount 口径对照用 */
    @Autowired
    private PaModelService modelService;

    @Autowired
    private PaSheetMapper sheetMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        for (String t : List.of("uf_pa_rating", "uf_pa_sheet_item", "uf_pa_sheet",
                "uf_pa_band", "uf_pa_item_level", "uf_pa_item", "uf_pa_category", "uf_pa_post")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("DELETE FROM HrmDepartment");
        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, canceled) VALUES (?, ?, ?, '0')",
                DEPT_HR, "人力资源部", "人力资源部");
        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, canceled) VALUES (?, ?, ?, '0')",
                DEPT_FIN, "财务部", "财务部");
        DimensionContext.set(DIM);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
    }

    private PaPostCreateDTO createDto(long departmentId, String postName) {
        PaPostCreateDTO dto = new PaPostCreateDTO();
        dto.setDepartmentId(departmentId);
        dto.setPostName(postName);
        return dto;
    }

    // ------------------------------------------------------------------ 创建

    @Test
    @DisplayName("创建岗位：部门名从泛微部门表落快照")
    void createResolvesDepartmentName() {
        Long id = postService.createPost(createDto(DEPT_HR, "人事专员"));

        PaPostVO vo = postService.getPostById(id);
        assertEquals("人力资源部", vo.getDepartmentName(), "部门名应取 HrmDepartment.departmentmark");
        assertEquals("人事专员", vo.getPostName());
        assertEquals(1, vo.getStatus());
        assertEquals(0, vo.getCategoryCount());
        assertEquals(0, vo.getItemCount());
        assertEquals(0, new BigDecimal(vo.getWeightTotal().toPlainString()).compareTo(BigDecimal.ZERO),
                "无能力项时权重合计为 0");
    }

    @Test
    @DisplayName("创建岗位：部门不存在或已撤销时拒绝")
    void createRejectsMissingDepartment() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> postService.createPost(createDto(9999L, "幽灵岗位")));
        assertTrue(ex.getMessage().contains("部门不存在"), "提示应说清是部门问题");

        jdbc.update("UPDATE HrmDepartment SET canceled = '1' WHERE id = ?", DEPT_FIN);
        assertThrows(BusinessException.class,
                () -> postService.createPost(createDto(DEPT_FIN, "财务专员")));
    }

    @Test
    @DisplayName("同部门内岗位名唯一，跨部门同名允许（口径 #1：各配一套）")
    void nameUniqueWithinDepartmentOnly() {
        postService.createPost(createDto(DEPT_HR, "专员"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> postService.createPost(createDto(DEPT_HR, "专员")));
        assertTrue(ex.getMessage().contains("已存在"), "同部门重名要明确拒绝");

        assertDoesNotThrow(() -> postService.createPost(createDto(DEPT_FIN, "专员")),
                "不同部门的同名岗位各自一套，不能当成重名");
    }

    // ------------------------------------------------------------------ 更新

    @Test
    @DisplayName("更新岗位：改部门时重新取部门名快照，且按改后组合校验唯一")
    void updateRefreshesSnapshotAndRechecksUniqueness() {
        Long id = postService.createPost(createDto(DEPT_HR, "专员"));
        postService.createPost(createDto(DEPT_FIN, "专员"));
        Long finOther = postService.createPost(createDto(DEPT_FIN, "主管"));

        // 改部门 → 部门名快照跟着换
        PaPostUpdateDTO dto = new PaPostUpdateDTO();
        dto.setId(id);
        dto.setDepartmentId(DEPT_FIN);
        dto.setPostName("专员B");
        postService.updatePost(dto);
        PaPostVO vo = postService.getPostById(id);
        assertEquals("财务部", vo.getDepartmentName(), "换部门后快照应为新部门名");
        assertEquals("专员B", vo.getPostName());

        // 改名撞上同部门已有岗位 → 拒绝
        PaPostUpdateDTO dup = new PaPostUpdateDTO();
        dup.setId(id);
        dup.setDepartmentId(DEPT_FIN);
        dup.setPostName("主管");
        assertThrows(BusinessException.class, () -> postService.updatePost(dup));
        assertNotNull(postService.getPostById(finOther));
    }

    @Test
    @DisplayName("更新岗位：不改部门时保留原部门名快照（不因泛微改名而漂移）")
    void updateKeepsSnapshotWhenDepartmentUnchanged() {
        Long id = postService.createPost(createDto(DEPT_HR, "专员"));
        // 泛微侧改名，历史快照不应跟着变
        jdbc.update("UPDATE HrmDepartment SET departmentmark = '人力资源中心' WHERE id = ?", DEPT_HR);

        PaPostUpdateDTO dto = new PaPostUpdateDTO();
        dto.setId(id);
        dto.setDepartmentId(DEPT_HR);
        dto.setPostName("高级专员");
        postService.updatePost(dto);

        assertEquals("人力资源部", postService.getPostById(id).getDepartmentName());
    }

    // ------------------------------------------------------------------ 统计

    @Test
    @DisplayName("列表统计：只数启用的类别与能力项，权重合计实时汇总")
    void statsCountOnlyEnabledAndSumWeight() {
        Long postId = postService.createPost(createDto(DEPT_HR, "人事专员"));

        long catOn = insertCategory(postId, "通用能力", 1);
        insertCategory(postId, "已停用类别", 0);
        insertItem(postId, catOn, "沟通", "30.0000", 1);
        insertItem(postId, catOn, "协调", "20.5000", 1);
        insertItem(postId, catOn, "已停用项", "99.0000", 0);

        PageResult<PaPostVO> page = postService.pagePosts(new PageRequest(), null, null, null, null);
        assertEquals(1, page.getTotal());
        PaPostVO vo = page.getList().get(0);
        assertEquals(1, vo.getCategoryCount(), "停用类别不计入");
        assertEquals(2, vo.getItemCount(), "停用能力项不计入");
        assertEquals(0, vo.getWeightTotal().compareTo(new BigDecimal("50.5000")),
                "权重合计只加启用项，且原样求和（不强制 100%）");
    }

    @Test
    @DisplayName("列表统计：等级不足 2 档的启用能力项被数出来（含一档都没配的）")
    void statsCountUnconfiguredLevels() {
        Long postId = postService.createPost(createDto(DEPT_HR, "人事专员"));
        long cat = insertCategory(postId, "通用能力", 1);

        long twoLevels = insertItem(postId, cat, "沟通", "25.0000", 1);
        insertLevel(twoLevels, "入门", 1, 0);
        insertLevel(twoLevels, "精进", 4, 1);

        // 最该报的那种：一个档位都没有，等级表里查不到行
        insertItem(postId, cat, "协调", "25.0000", 1);

        long oneLevel = insertItem(postId, cat, "表达", "25.0000", 1);
        insertLevel(oneLevel, "入门", 1, 0);

        // 停用项即使等级没配也不算 —— 它不会进测评页
        insertItem(postId, cat, "已停用项", "25.0000", 0);

        PaPostVO vo = postService.pagePosts(new PageRequest(), null, null, null, null).getList().get(0);
        assertEquals(3, vo.getItemCount(), "3 个启用项");
        assertEquals(2, vo.getUnconfiguredItemCount(),
                "「协调」0 档 + 「表达」1 档 = 2 项；2 档的与停用项都不算");
    }

    @Test
    @DisplayName("列表统计：等级全部配好时为 0，详情页同口径")
    void statsUnconfiguredMatchesModelDetail() {
        Long postId = postService.createPost(createDto(DEPT_HR, "人事专员"));
        long cat = insertCategory(postId, "通用能力", 1);
        long item = insertItem(postId, cat, "沟通", "100.0000", 1);
        insertLevel(item, "入门", 1, 0);
        insertLevel(item, "精进", 4, 1);

        PaPostVO vo = postService.getPostById(postId);
        assertEquals(0, vo.getUnconfiguredItemCount());
        assertEquals(vo.getUnconfiguredItemCount(), modelService.getModel(postId).getUnconfiguredItemCount(),
                "列表与详情必须同一口径，否则列表说配好了、点进去说没配好");
    }

    @Test
    @DisplayName("列表筛选：部门 / 关键词（岗位名、编码、部门名）/ 状态")
    void pageFilters() {
        PaPostCreateDTO hr = createDto(DEPT_HR, "人事专员");
        hr.setPostCode("HR-01");
        postService.createPost(hr);
        PaPostCreateDTO fin = createDto(DEPT_FIN, "财务专员");
        fin.setStatus(0);
        postService.createPost(fin);

        assertEquals(1, postService.pagePosts(new PageRequest(), DEPT_HR, null, null, null).getTotal(),
                "按部门筛选");
        assertEquals(1, postService.pagePosts(new PageRequest(), null, "HR-01", null, null).getTotal(),
                "按岗位编码搜");
        assertEquals(2, postService.pagePosts(new PageRequest(), null, "专员", null, null).getTotal(),
                "按岗位名搜");
        assertEquals(1, postService.pagePosts(new PageRequest(), null, "财务部", null, null).getTotal(),
                "按部门名快照搜");
        assertEquals(1, postService.pagePosts(new PageRequest(), null, null, 0, null).getTotal(),
                "按状态筛选");
    }

    @Test
    @DisplayName("下拉列表只返回启用岗位，且可按部门收窄")
    void listReturnsEnabledOnly() {
        postService.createPost(createDto(DEPT_HR, "人事专员"));
        PaPostCreateDTO off = createDto(DEPT_HR, "停用岗位");
        off.setStatus(0);
        postService.createPost(off);
        postService.createPost(createDto(DEPT_FIN, "财务专员"));

        assertEquals(2, postService.listPosts(null).size(), "停用的不出现在下拉里");
        assertEquals(1, postService.listPosts(DEPT_HR).size());
        assertEquals("人事专员", postService.listPosts(DEPT_HR).get(0).getPostName());
    }

    // ------------------------------------------------------------------ 全公司通用岗位（sql/63）

    @Test
    @DisplayName("通用岗位：不选部门即可创建，不查部门表，部门快照为空")
    void createGenericPostWithoutDepartment() {
        PaPostCreateDTO dto = new PaPostCreateDTO();
        dto.setDepartmentId(null);
        dto.setPostName("会计");
        Long id = postService.createPost(dto);

        PaPostVO vo = postService.getPostById(id);
        assertNull(vo.getDepartmentId(), "通用岗位没有部门");
        assertNull(vo.getDepartmentName(), "部门名快照也应为空");
        assertEquals(Boolean.TRUE, vo.getGeneric(), "generic 由 departmentId 推导");
    }

    @Test
    @DisplayName("通用岗位：同名全公司唯一（库里两条 NULL 部门会撞唯一索引）")
    void genericPostNameUniqueGlobally() {
        PaPostCreateDTO dto = new PaPostCreateDTO();
        dto.setPostName("会计");
        postService.createPost(dto);

        BusinessException ex = assertThrows(BusinessException.class, () -> postService.createPost(dto));
        assertTrue(ex.getMessage().contains("全公司通用"), "提示要说清是通用岗位重名");

        // 同名但落在具体部门上仍然允许：销售部的「会计」和通用「会计」不是一回事
        assertDoesNotThrow(() -> postService.createPost(createDto(DEPT_HR, "会计")));
    }

    @Test
    @DisplayName("通用岗位：把已有岗位改为通用，部门与部门名快照都被清空")
    void updateToGenericClearsDepartment() {
        Long id = postService.createPost(createDto(DEPT_HR, "会计"));

        PaPostUpdateDTO dto = new PaPostUpdateDTO();
        dto.setId(id);
        dto.setDepartmentId(null);
        dto.setPostName("会计");
        postService.updatePost(dto);

        PaPostVO vo = postService.getPostById(id);
        assertNull(vo.getDepartmentId(), "updateById 的 NOT_NULL 策略会跳过 null，必须显式写 NULL");
        assertNull(vo.getDepartmentName());
        assertEquals(Boolean.TRUE, vo.getGeneric());
    }

    @Test
    @DisplayName("通用岗位：列表可按 generic 筛选，排序排在部门岗位之后")
    void pageFiltersGenericAndSortsLast() {
        postService.createPost(createDto(DEPT_HR, "人事专员"));
        PaPostCreateDTO generic = new PaPostCreateDTO();
        generic.setPostName("会计");
        postService.createPost(generic);

        assertEquals(1, postService.pagePosts(new PageRequest(), null, null, null, true).getTotal(),
                "只看通用岗位");
        assertEquals(1, postService.pagePosts(new PageRequest(), null, null, null, false).getTotal(),
                "只看部门岗位");
        assertEquals(2, postService.pagePosts(new PageRequest(), null, null, null, null).getTotal());

        List<PaPostVO> all = postService.pagePosts(new PageRequest(), null, null, null, null).getList();
        assertEquals("人事专员", all.get(0).getPostName(),
                "有部门的排前面 —— SQL Server 的 ASC 会把 NULL 排最前，通用岗位应当压到后面");
        assertEquals("会计", all.get(1).getPostName());
    }

    @Test
    @DisplayName("通用岗位：发起页下拉单独出一个接口，不影响按部门的下拉")
    void listGenericPostsSeparateFromDepartmentList() {
        postService.createPost(createDto(DEPT_HR, "人事专员"));
        PaPostCreateDTO generic = new PaPostCreateDTO();
        generic.setPostName("会计");
        postService.createPost(generic);

        assertEquals(1, postService.listGenericPosts().size());
        assertEquals("会计", postService.listGenericPosts().get(0).getPostName());
        assertEquals(1, postService.listPosts(DEPT_HR).size(), "按部门查不该混进通用岗位");

        // 停用的通用岗位不出现在发起页
        PaPostUpdateDTO off = new PaPostUpdateDTO();
        off.setId(postService.listGenericPosts().get(0).getId());
        off.setDepartmentId(null);
        off.setPostName("会计");
        off.setStatus(0);
        postService.updatePost(off);
        assertEquals(0, postService.listGenericPosts().size());
    }

    // ------------------------------------------------------------------ 删除

    @Test
    @DisplayName("删除岗位：级联软删其类别与能力项")
    void deleteCascadesConfig() {
        Long postId = postService.createPost(createDto(DEPT_HR, "人事专员"));
        long catId = insertCategory(postId, "通用能力", 1);
        insertItem(postId, catId, "沟通", "100.0000", 1);

        postService.deletePost(postId);

        assertNull(postService.getById(postId), "岗位应被逻辑删除");
        assertEquals(0, categoryMapper.selectCount(null), "类别应随之软删");
        assertEquals(0, itemMapper.selectCount(null), "能力项应随之软删");
    }

    @Test
    @DisplayName("删除岗位：有测评页引用时拒绝，提示改为停用（口径 #15）")
    void deleteRejectedWhenReferenced() {
        Long postId = postService.createPost(createDto(DEPT_HR, "人事专员"));

        PaSheet sheet = new PaSheet();
        sheet.setCompanyId(1L);
        sheet.setDimensionId(DIM);
        sheet.setPeriodId(1L);
        sheet.setResourceId(100L);
        sheet.setPostId(postId);
        sheetMapper.insert(sheet);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> postService.deletePost(postId), "测评页引用要拦");
        assertTrue(ex.getMessage().contains("不能删除"), "被引用时要明确拒绝");
        assertTrue(ex.getMessage().contains("停用"), "并告诉用户替代做法");
        assertNotNull(postService.getById(postId), "拒绝后岗位仍在");
    }

    @Test
    @DisplayName("查询不存在的岗位抛业务异常")
    void getMissingThrows() {
        assertThrows(BusinessException.class, () -> postService.getPostById(123456L));
        assertThrows(BusinessException.class, () -> postService.deletePost(123456L));
    }

    // ------------------------------------------------------------------ 种子

    private long insertCategory(long postId, String name, int status) {
        PaCategory c = new PaCategory();
        c.setDimensionId(DIM);
        c.setPostId(postId);
        c.setCategoryName(name);
        c.setStatus(status);
        c.setSort(0);
        categoryMapper.insert(c);
        return c.getId();
    }

    private long insertItem(long postId, long categoryId, String name, String weight, int status) {
        PaItem i = new PaItem();
        i.setDimensionId(DIM);
        i.setPostId(postId);
        i.setCategoryId(categoryId);
        i.setItemName(name);
        i.setWeight(new BigDecimal(weight));
        i.setRaterLeader(1);
        i.setRater360(0);
        i.setStatus(status);
        i.setSort(0);
        itemMapper.insert(i);
        return i.getId();
    }

    private void insertLevel(long itemId, String levelName, int score, int sort) {
        PaItemLevel l = new PaItemLevel();
        l.setDimensionId(DIM);
        l.setItemId(itemId);
        l.setLevelName(levelName);
        l.setScore(new BigDecimal(score));
        l.setSort(sort);
        levelMapper.insert(l);
    }
}
