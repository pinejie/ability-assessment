package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaTaskDTO;
import com.company.ability.vo.PaTaskVO;
import com.company.ability.support.PaTestData;
import com.company.ability.dto.PaImportDraft;
import com.company.ability.dto.PaImportPlan;
import com.company.ability.dto.PaImportPreview;
import com.company.ability.entity.PaBand;
import com.company.ability.entity.PaCategory;
import com.company.ability.entity.PaItem;
import com.company.ability.entity.PaItemLevel;
import com.company.ability.entity.PaPost;
import com.company.ability.entity.PaRating;
import com.company.ability.entity.PaSheetItem;
import com.company.ability.mapper.PaBandMapper;
import com.company.ability.mapper.PaCategoryMapper;
import com.company.ability.mapper.PaItemLevelMapper;
import com.company.ability.mapper.PaItemMapper;
import com.company.ability.mapper.PaPostMapper;
import com.company.ability.mapper.PaRatingMapper;
import com.company.ability.mapper.PaSheetItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入写入链路测试：三类写分支 + 能力项匹配 + 「有引用则停用」。
 *
 * <p>重点保护的是三条不可逆行为，它们错了都会让历史数据失去解释力：</p>
 * <ol>
 *   <li><b>能力项三级匹配</b>（编码+名称 → 编码 → 名称）：编码对上但改了名，必须原地更新
 *       （保住 item_id），不能删了重建 —— 重建后历史评级全指向已删除的行。
 *       编码<b>允许重复</b>（sql/65），所以不能只按编码建 Map，否则同编码的两项会互相顶掉</li>
 *   <li><b>等级按分值匹配</b>：分值对上但改档名/描述，同样要保住 level_id（评级表引用它）</li>
 *   <li><b>文件里没有的能力项/等级，有引用就停用、没引用才删</b></li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("能力结构导入写入测试")
class PaImportApplierTest {

    private static final long DIM = 1L;
    private static final long DEPT = 601L;

    @Autowired
    private PaImportApplier applier;
    @Autowired
    private PaImportPlanner planner;
    @Autowired
    private PaImportValidator validator;
    @Autowired
    private PaPostMapper postMapper;
    @Autowired
    private PaCategoryMapper categoryMapper;
    @Autowired
    private PaItemMapper itemMapper;
    @Autowired
    private PaItemLevelMapper levelMapper;
    @Autowired
    private PaBandMapper bandMapper;
    @Autowired
    private PaRatingMapper ratingMapper;
    @Autowired
    private PaSheetItemMapper sheetItemMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PaPostService postService;
    @Autowired
    private PaModelService modelService;
    @Autowired
    private PaTaskService taskService;

    @BeforeEach
    void setUp() {
        for (String t : List.of("uf_pa_rating", "uf_pa_sheet_item", "uf_pa_sheet", "uf_assess_task_rater",
                "uf_assess_task", "uf_assess_period", "uf_pa_band", "uf_pa_item_level", "uf_pa_item",
                "uf_pa_category", "uf_pa_post", "uf_pa_import_session", "HrmResource", "HrmDepartment")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, canceled)" +
                " VALUES (?, ?, ?, '0')", DEPT, "质量部", "质量部");
        DimensionContext.set(DIM);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
    }

    // ------------------------------------------------------------------ 新建

    @Test
    @DisplayName("库里没有这个岗位 → CREATE，类别/能力项/等级/区间全部落库")
    void createFromScratch() {
        PaImportDraft draft = onePost("质量工程师", item("Q1", "检验能力", "14", 1, 0, "直接上级",
                level("入门/新手", "1"), level("精进/能手", "2"), level("专家", "3")));

        PaImportPreview preview = planner.preview(draft, plan());
        assertEquals("CREATE", preview.getPosts().get(0).getAction());

        PaImportApplier.Result result = applier.apply(draft, plan(), preview);
        assertEquals(1, result.getCreated().size());
        assertEquals(1, result.getItemCreated());

        PaPost post = postMapper.selectList(new LambdaQueryWrapper<PaPost>()
                .eq(PaPost::getDepartmentId, DEPT)).get(0);
        assertEquals("质量工程师", post.getPostName());
        assertEquals("质量部", post.getDepartmentName(), "部门名要落快照");

        List<PaItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, post.getId()));
        assertEquals(1, items.size());
        assertEquals(0, new BigDecimal("14").compareTo(items.get(0).getWeight()));
        assertEquals("直接上级", items.get(0).getRaterText(), "评价主体原文要落库，页面靠它显示业务写法");

        List<PaItemLevel> levels = levelMapper.selectList(new LambdaQueryWrapper<PaItemLevel>()
                .eq(PaItemLevel::getItemId, items.get(0).getId()));
        assertEquals(3, levels.size());

        List<PaBand> bands = bandMapper.selectList(new LambdaQueryWrapper<PaBand>()
                .eq(PaBand::getPostId, post.getId()));
        assertEquals(3, bands.size());
        assertNull(bands.get(bands.size() - 1).getUpperBound(), "最高一档上界必须开口");
    }

    // ------------------------------------------------------------------ 覆盖

    @Test
    @DisplayName("校验：编码重复不再算阻断（sql/65），只有超长才拦")
    void validatorAllowsDuplicateCodes() {
        PaImportDraft draft = onePost("综合服务员",
                item("E01", "服务意识", "50", 1, 0, level("新手", "1"), level("能手", "2")),
                item("E01", "抗压能力", "50", 1, 0, level("新手", "1"), level("能手", "2")));

        assertEquals(List.of(), validator.checkPost(draft.getPosts().get(0)),
                "编码重复是模板的正常写法，不该报问题");
    }

    @Test
    @DisplayName("新建：同岗位内编码重复，两项都要落库（sql/65）")
    void createKeepsDuplicateCodes() {
        // 模板的真实写法：专业能力 A01…G01，职业素养起又从 E01 重来
        PaImportDraft draft = onePost("综合服务员",
                item("E01", "服务意识", "30", 1, 0, level("新手", "1"), level("能手", "2")),
                item("F01", "表达能力", "30", 1, 0, level("新手", "1"), level("能手", "2")),
                item("E01", "抗压能力", "40", 1, 0, level("新手", "1"), level("能手", "2")));

        PaImportPreview preview = planner.preview(draft, plan());
        assertEquals("CREATE", preview.getPosts().get(0).getAction());

        PaImportApplier.Result result = applier.apply(draft, plan(), preview);
        assertEquals(3, result.getItemCreated(), "三个能力项都要建，重复编码不能互相顶掉");

        PaPost post = postMapper.selectList(new LambdaQueryWrapper<PaPost>()).get(0);
        List<PaItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, post.getId()));
        assertEquals(3, items.size());
        assertEquals(2, items.stream().filter(i -> "E01".equals(i.getItemCode())).count(),
                "同岗位里两个 E01 都要在");
    }

    @Test
    @DisplayName("覆盖：编码重复时靠名称分辨，四项各自对上自己那行，不会互相覆盖")
    void overwriteWithDuplicateCodesMatchesByName() {
        PaImportDraft first = onePost("综合服务员",
                item("E01", "服务意识", "30", 1, 0, level("新手", "1"), level("能手", "2")),
                item("E01", "抗压能力", "40", 1, 0, level("新手", "1"), level("能手", "2")));
        applier.apply(first, plan(), planner.preview(first, plan()));
        assertEquals(2, itemMapper.selectList(new LambdaQueryWrapper<PaItem>()).size());

        PaPost post = postMapper.selectList(new LambdaQueryWrapper<PaPost>()).get(0);
        Long serviceId = byName(post.getId(), "服务意识").getId();
        Long stressId = byName(post.getId(), "抗压能力").getId();

        // 再导一次：两项都改权重，编码不动
        PaImportDraft second = onePost("综合服务员",
                item("E01", "服务意识", "60", 1, 0, level("新手", "1"), level("能手", "2")),
                item("E01", "抗压能力", "40", 1, 0, level("新手", "1"), level("能手", "2")));
        PaImportPreview p2 = planner.preview(second, plan());
        assertEquals("OVERWRITE", p2.getPosts().get(0).getAction());
        assertEquals(0, p2.getPosts().get(0).getAdded().size(), "两项都该匹配上，没有新增");
        assertEquals(0, p2.getPosts().get(0).getRemoved().size(), "两项都该匹配上，没有移除");
        assertEquals(1, p2.getPosts().get(0).getChanged().size(), "只有改了权重的「服务意识」算有差异");

        applier.apply(second, plan(), p2);

        List<PaItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, post.getId()));
        assertEquals(2, items.size(), "重复编码不能让能力项变少");
        assertEquals(serviceId, byName(post.getId(), "服务意识").getId(), "item_id 要保住，历史评级不能失联");
        assertEquals(stressId, byName(post.getId(), "抗压能力").getId());
        assertEquals(0, new BigDecimal("60").compareTo(byName(post.getId(), "服务意识").getWeight()));
        assertEquals(0, new BigDecimal("40").compareTo(byName(post.getId(), "抗压能力").getWeight()));
    }

    @Test
    @DisplayName("覆盖：库里既有项编码重复时（历史数据），按名称也能各对各的")
    void overwriteHandlesDuplicateCodesAlreadyInDb() {
        PaImportDraft first = onePost("综合服务员",
                item("E01", "服务意识", "30", 1, 0, level("新手", "1"), level("能手", "2")),
                item("E01", "抗压能力", "40", 1, 0, level("新手", "1"), level("能手", "2")));
        applier.apply(first, plan(), planner.preview(first, plan()));
        PaPost post = postMapper.selectList(new LambdaQueryWrapper<PaPost>()).get(0);
        Long serviceId = byName(post.getId(), "服务意识").getId();

        // 库里两个 E01，文件里只留「服务意识」并改名 —— 必须更新它，把「抗压能力」移除，
        // 而不是因为 byCode 被后者覆盖而把两个都当成不存在
        PaImportDraft second = onePost("综合服务员",
                item("E01", "客户服务意识", "30", 1, 0, level("新手", "1"), level("能手", "2")));
        applier.apply(second, plan(), planner.preview(second, plan()));

        List<PaItem> alive = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, post.getId()).eq(PaItem::getDeleted, 0));
        assertEquals(1, alive.size());
        assertEquals(serviceId, alive.get(0).getId());
        assertEquals("客户服务意识", alive.get(0).getItemName());
    }

    @Test
    @DisplayName("覆盖：编码匹配上就原地更新，item_id / level_id 不变（历史评级不失联）")
    void overwriteKeepsIdentity() {
        // 先建一次
        PaImportDraft first = onePost("质量工程师", item("Q1", "检验能力", "100", 1, 0, "直接上级",
                level("新手", "1"), level("能手", "2")));
        PaImportPreview p1 = planner.preview(first, plan());
        applier.apply(first, plan(), p1);

        PaPost post = postMapper.selectList(new LambdaQueryWrapper<PaPost>()).get(0);
        PaItem oldItem = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()).get(0);
        PaItemLevel oldLevel = levelMapper.selectList(new LambdaQueryWrapper<PaItemLevel>()
                .eq(PaItemLevel::getItemId, oldItem.getId())
                .orderByAsc(PaItemLevel::getSort)).get(0);
        Long oldItemId = oldItem.getId();
        Long oldLevelId = oldLevel.getId();

        // 再导一次：同名能力项（编码 Q1）改了名、改了权重；等级 1 分改了档名
        PaImportDraft second = onePost("质量工程师", item("Q1", "检验与试验能力", "100", 1, 1,
                "内部客户360＋直接上级", level("入门/新手", "1"), level("精进/能手", "2")));
        PaImportPreview p2 = planner.preview(second, plan());
        assertEquals("OVERWRITE", p2.getPosts().get(0).getAction());
        assertEquals(1, p2.getPosts().get(0).getChanged().size());

        applier.apply(second, plan(), p2);

        List<PaItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, post.getId()));
        assertEquals(1, items.size(), "编码一致的项必须原地更新，不能删了重建");
        assertEquals(oldItemId, items.get(0).getId());
        assertEquals("检验与试验能力", items.get(0).getItemName());
        assertEquals(1, items.get(0).getRater360());
        assertEquals("内部客户360＋直接上级", items.get(0).getRaterText(),
                "覆盖导入时原文也要跟着换，否则标签和描述会自相矛盾");

        List<PaItemLevel> levels = levelMapper.selectList(new LambdaQueryWrapper<PaItemLevel>()
                .eq(PaItemLevel::getItemId, oldItemId).orderByAsc(PaItemLevel::getSort));
        assertEquals(2, levels.size());
        assertEquals(oldLevelId, levels.get(0).getId(), "分值一致的档必须保住 level_id（评级表引用它）");
        assertEquals("入门/新手", levels.get(0).getLevelName());
    }

    @Test
    @DisplayName("覆盖：文件里没有的能力项，无引用则删、有引用则停用")
    void removedItemRespectsReferences() {
        // 建三个项：Q1 会被引用（模拟历史评级）、Q2 保留在新模板里、Q3 无人引用
        PaImportDraft first = onePost("质量工程师",
                item("Q1", "检验能力", "30", 1, 0, level("新手", "1"), level("能手", "2")),
                item("Q2", "判定能力", "40", 1, 0, level("新手", "1"), level("能手", "2")),
                item("Q3", "沟通能力", "30", 1, 0, level("新手", "1"), level("能手", "2")));
        applier.apply(first, plan(), planner.preview(first, plan()));

        PaPost post = postMapper.selectList(new LambdaQueryWrapper<PaPost>()).get(0);
        // 按编码取，不能按 sort 取：三项 sort 都是 0，靠顺序取会拿错人
        PaItem keepReferenced = byCode(post.getId(), "Q1");
        PaItem removedClean = byCode(post.getId(), "Q3");

        // 给「检验能力」造一条评级引用（模拟历史测评）
        PaRating rating = new PaRating();
        rating.setDimensionId(DIM);
        rating.setSheetId(999L);
        rating.setItemId(keepReferenced.getId());
        rating.setRaterType(PaRating.TYPE_LEADER);
        rating.setRaterResourceId(1L);
        rating.setLevelId(levelMapper.selectList(new LambdaQueryWrapper<PaItemLevel>()
                .eq(PaItemLevel::getItemId, keepReferenced.getId())).get(0).getId());
        rating.setScore(new BigDecimal("1"));
        ratingMapper.insert(rating);

        // 新模板只剩 Q2（Q1 与 Q3 都被删掉了）
        PaImportDraft second = onePost("质量工程师",
                item("Q2", "判定能力", "100", 1, 0, level("新手", "1"), level("能手", "2")));
        PaImportApplier.Result result = applier.apply(second, plan(), planner.preview(second, plan()));

        assertEquals(1, result.getItemDisabled(), "有评级引用的能力项只能停用");
        assertEquals(1, result.getItemRemoved(), "没引用的能力项可以删");

        PaItem referenced = itemMapper.selectById(keepReferenced.getId());
        assertNotNull(referenced, "被引用的能力项不能物理删除");
        assertEquals(0, referenced.getStatus(), "应改为停用");

        PaItem gone = itemMapper.selectById(removedClean.getId());
        assertTrue(gone == null || gone.getDeleted() == 1, "无引用的能力项应被逻辑删除");
    }

    private PaItem byCode(Long postId, String code) {
        return itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, postId).eq(PaItem::getItemCode, code)).get(0);
    }

    /** 按名称取（编码允许重复后，同岗位里一个编码可能对应多项，见 sql/65） */
    private PaItem byName(Long postId, String name) {
        return itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, postId).eq(PaItem::getItemName, name)).get(0);
    }

    @Test
    @DisplayName("跳过模式：库里已有同名岗位时一行都不写")
    void skipModeWritesNothing() {
        PaImportDraft first = onePost("质量工程师", item("Q1", "检验能力", "100", 1, 0,
                level("新手", "1"), level("能手", "2")));
        applier.apply(first, plan(), planner.preview(first, plan()));
        PaItem before = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()).get(0);

        PaImportPlan skip = plan();
        skip.setExisting(PaImportPlan.SKIP);

        PaImportDraft second = onePost("质量工程师", item("Q1", "改了名的能力项", "100", 1, 0,
                level("新手", "1"), level("能手", "2")));
        PaImportPreview preview = planner.preview(second, skip);
        assertEquals("SKIP", preview.getPosts().get(0).getAction());

        PaImportApplier.Result result = applier.apply(second, skip, preview);
        assertEquals(1, result.getSkipped().size());
        assertTrue(result.getOverwritten().isEmpty());

        PaItem after = itemMapper.selectById(before.getId());
        assertEquals("检验能力", after.getItemName(), "跳过模式下库里的内容必须原样不动");
    }

    @Test
    @DisplayName("不同部门可以同名岗位：不判冲突")
    void samePostNameInOtherDepartmentIsNotConflict() {
        long otherDept = 602L;
        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, canceled)" +
                " VALUES (?, ?, ?, '0')", otherDept, "生产部", "生产部");

        PaImportDraft inQuality = onePost(DEPT, "质量工程师", item("Q1", "检验能力", "100", 1, 0,
                level("新手", "1"), level("能手", "2")));
        applier.apply(inQuality, plan(), planner.preview(inQuality, plan()));

        PaImportDraft inProduction = onePost(otherDept, "质量工程师", item("Q1", "检验能力", "100", 1, 0,
                level("新手", "1"), level("能手", "2")));
        PaImportPreview preview = planner.preview(inProduction, plan());

        assertEquals("CREATE", preview.getPosts().get(0).getAction(),
                "唯一口径是部门+岗位，别的部门有同名岗位不算冲突");
        assertNull(validator.findExistingPost(otherDept, "质量工程师"));
    }

    // ------------------------------------------------------------------ 测评影响

    /**
     * 撤回 + 重发之后，旧任务上必须留下「被谁替代」的线索。
     *
     * <p>这条链接以前全代码库都没人写：撤回把旧任务置成 4、重发建出新任务 0，
     * 两条行除了备注里的文字看不出是一回事，事后想追「这个人的重评落在哪」只能靠猜。
     * 只在撤回重发这条链路上有它，刷新/重算不涉及任务，所以就在这里测。</p>
     *
     * <p>这里走真实的 {@link PaTaskService#create}：重发链路上的发起闸门（年度、上级、岗位、
     * 部门匹配）一个都不能跳，用假 taskId 蒙过去就测不到真正会失败的那一段。</p>
     */
    @Test
    @DisplayName("撤回进行中任务并重发后，旧任务记下 replaced_by_task_id")
    void relaunchBackfillsReplacementLink() {
        PaTestData data = new PaTestData(jdbc);
        data.reset();
        long periodId = data.seedPeriod(postService, 0);
        data.seedPeople();
        DimensionContext.set(PaTestData.DIM);

        // ① 先有一个岗位和一条进行中的测评任务
        long postId = data.seedPost(postService, modelService, "质量工程师");
        long cat = data.category(modelService, postId, "专业能力");
        data.item(modelService, postId, cat, "检验能力", "100", 1, 1);
        data.bands(modelService, postId);

        PaTaskDTO.CreateRequest req = new PaTaskDTO.CreateRequest();
        req.setYear(PaTestData.YEAR);
        req.setResourceId(PaTestData.TARGET);
        req.setPostId(postId);
        req.setRater360ResourceIds(List.of(PaTestData.RATER_A));
        PaTaskVO.CreateResult task = taskService.create(req);
        assertNotNull(task.getTaskId());

        // ② 导入同一岗位的新模型，并要求撤回 + 重发
        //    必须是 OVERWRITE：planner 只把「真要改模型」的岗位算进影响面，
        //    选了跳过就等于模型没动，那也不该去撤回别人的测评
        PaImportPlan voidAndRelaunch = plan();
        voidAndRelaunch.setExisting(PaImportPlan.OVERWRITE);
        voidAndRelaunch.setVoidRunning(true);
        voidAndRelaunch.setRelaunchRunning(true);
        voidAndRelaunch.setVoidReason("能力模型更新，按新模型重新发起");

        PaImportDraft draft = onePost(PaTestData.DEPT, "质量工程师",
                item("Q1", "检验能力", "100", 1, 1, level("新手", "1"), level("能手", "2")));
        PaImportPreview preview = planner.preview(draft, voidAndRelaunch);
        assertEquals(1, preview.getImpact().getRunningCount(), "刚发起的任务应该被识别成进行中");
        assertEquals(periodId, jdbc.queryForObject(
                "SELECT period_id FROM uf_assess_task WHERE id = ?", Long.class, task.getTaskId()));

        PaImportApplier.Result result = applier.apply(draft, voidAndRelaunch, preview);
        assertEquals(1, result.getVoidedTaskIds().size());
        assertEquals(1, result.getRelaunchedTaskIds().size(), "重发失败会在 warnings 里说，不该静默少一条");

        long newTaskId = result.getRelaunchedTaskIds().get(0);
        assertEquals(newTaskId, jdbc.queryForObject(
                "SELECT replaced_by_task_id FROM uf_assess_task WHERE id = ?",
                Long.class, task.getTaskId()),
                "旧任务必须记住被哪条新任务替代，否则撤回重发之后查不出前后关系");
        assertEquals(4, jdbc.queryForObject(
                "SELECT status FROM uf_assess_task WHERE id = ?", Integer.class, task.getTaskId()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT status FROM uf_assess_task WHERE id = ?", Integer.class, newTaskId));
    }

    // ------------------------------------------------------------------ 阻断

    @Test
    @DisplayName("权重合计不是 100% 时有阻断项，预览里能看到")
    void weightSumBlocked() {
        PaImportDraft draft = onePost("质量工程师",
                item("Q1", "检验能力", "60", 1, 0, level("新手", "1"), level("能手", "2")),
                item("Q2", "判定能力", "30", 1, 0, level("新手", "1"), level("能手", "2")));

        List<com.company.ability.dto.PaImportQuestion> questions =
                new PaImportQuestionBuilder().build(draft, PaImportQuestionBuilder.none());
        assertTrue(questions.stream().anyMatch(q -> q.getSlot().contains(".weight") && q.isBlocking()),
                "权重合计 90% 必须报成阻断项");
    }

    // ------------------------------------------------------------------ 全公司通用

    @Test
    @DisplayName("通用岗位（部门为空）→ 落成 department_id = NULL，不碰部门表")
    void createGenericPost() {
        // 部门给 null = 用户选了「全公司通用」
        PaImportDraft draft = onePost(null, "会计", item("K1", "核算能力", "100", 1, 0,
                level("入门/新手", "1"), level("精进/能手", "2")));

        PaImportPreview preview = planner.preview(draft, plan());
        assertEquals("CREATE", preview.getPosts().get(0).getAction());
        assertEquals(PaImportQuestionBuilder.GENERIC_DEPARTMENT,
                preview.getPosts().get(0).getDepartmentName(), "预览部门列要显式写出全公司通用");

        PaImportApplier.Result result = applier.apply(draft, plan(), preview);
        assertEquals(1, result.getCreated().size());

        PaPost post = postMapper.selectList(new LambdaQueryWrapper<PaPost>()
                .eq(PaPost::getPostName, "会计")).get(0);
        assertNull(post.getDepartmentId(), "通用岗位的 department_id 必须是 NULL（sql/63）");
        assertNull(post.getDepartmentName(), "通用岗位不该有部门名快照");
    }

    @Test
    @DisplayName("通用岗位重复导入 → 能查到已存在的通用岗位，走覆盖而不是撞唯一索引")
    void genericPostIsFoundForOverwrite() {
        PaImportDraft first = onePost(null, "会计", item("K1", "核算能力", "100", 1, 0,
                level("入门/新手", "1"), level("精进/能手", "2")));
        applier.apply(first, plan(), planner.preview(first, plan()));

        PaImportDraft second = onePost(null, "会计", item("K1", "核算与报表能力", "100", 1, 0,
                level("入门/新手", "1"), level("精进/能手", "2")));
        PaImportPreview preview = planner.preview(second, plan());

        assertEquals("OVERWRITE", preview.getPosts().get(0).getAction(),
                "同名通用岗位已存在时必须走覆盖，不然写库会撞 UX_pa_post_name");

        applier.apply(second, plan(), preview);
        List<PaPost> posts = postMapper.selectList(new LambdaQueryWrapper<PaPost>()
                .eq(PaPost::getPostName, "会计"));
        assertEquals(1, posts.size(), "通用岗位全公司只允许一条同名");
        assertNull(posts.get(0).getDepartmentId());
    }

    @Test
    @DisplayName("部门岗位与通用岗位是两个锚点：同名文件导入通用时新建，不改动原部门岗位")
    void genericDoesNotConvertDepartmentPost() {
        PaImportDraft first = onePost("质量工程师", item("Q1", "检验能力", "100", 1, 0,
                level("新手", "1"), level("能手", "2")));
        applier.apply(first, plan(), planner.preview(first, plan()));
        assertEquals(DEPT, postMapper.selectList(new LambdaQueryWrapper<PaPost>())
                .get(0).getDepartmentId());

        // 同一岗位名，但这次是通用岗位。唯一键是 (维度, 部门, 岗位名)，
        // 部门不同（这里是一个有部门、一个空）就是两条不同的岗位 —— 不能因为名字一样就去改原岗位
        PaImportDraft second = onePost(null, "质量工程师", item("Q1", "检验能力", "100", 1, 0,
                level("新手", "1"), level("能手", "2")));
        PaImportPreview preview = planner.preview(second, plan());
        assertEquals("CREATE", preview.getPosts().get(0).getAction(),
                "部门岗位不会被就地改成通用，那是两条独立岗位");

        applier.apply(second, plan(), preview);

        List<PaPost> posts = postMapper.selectList(new LambdaQueryWrapper<PaPost>()
                .eq(PaPost::getPostName, "质量工程师"));
        assertEquals(2, posts.size(), "部门岗位 + 通用岗位各一条");
        PaPost deptPost = posts.stream().filter(p -> p.getDepartmentId() != null).findFirst().orElseThrow();
        PaPost genericPost = posts.stream().filter(p -> p.getDepartmentId() == null).findFirst().orElseThrow();
        assertEquals(DEPT, deptPost.getDepartmentId(), "原部门岗位不能被动到");
        assertNull(genericPost.getDepartmentName(), "通用岗位不该有部门名快照");
    }

    // ------------------------------------------------------------------ 造数据

    private PaImportPlan plan() {
        PaImportPlan plan = new PaImportPlan();
        plan.setExisting(PaImportPlan.OVERWRITE);
        return plan;
    }

    private PaImportDraft onePost(String postName, PaImportDraft.ItemDraft... items) {
        return onePost(DEPT, postName, items);
    }

    private PaImportDraft onePost(Long deptId, String postName, PaImportDraft.ItemDraft... items) {
        PaImportDraft draft = new PaImportDraft();
        PaImportDraft.PostDraft post = new PaImportDraft.PostDraft();
        post.setSheetName("SHEET-" + postName);
        post.setPostName(postName);
        post.setDepartmentId(deptId);
        // 部门为空 = 全公司通用岗位，部门名也必须是空（库里存 NULL，见 sql/63）。
        // 注意不能写 deptId == DEPT：deptId 是 Long，与 long 常量比会先拆箱，传 null 就 NPE
        post.setDepartmentName(deptId == null ? null : (deptId == DEPT ? "质量部" : "生产部"));

        PaImportDraft.CategoryDraft category = new PaImportDraft.CategoryDraft();
        category.setCategoryName("专业能力");
        category.setSort(0);
        post.getCategories().add(category);
        for (PaImportDraft.ItemDraft item : items) {
            item.setCategoryName("专业能力");
            post.getItems().add(item);
        }

        // 区间按分值生成（与装配器的 FROM_LEVELS 同口径）
        java.util.TreeSet<BigDecimal> scores = new java.util.TreeSet<>();
        for (PaImportDraft.ItemDraft item : items) {
            for (PaImportDraft.LevelDraft lv : item.getLevels()) {
                scores.add(lv.getScore());
            }
        }
        List<BigDecimal> sorted = new java.util.ArrayList<>(scores);
        for (int i = 0; i < sorted.size(); i++) {
            PaImportDraft.BandDraft band = new PaImportDraft.BandDraft();
            band.setBandName("档" + sorted.get(i).toPlainString());
            band.setLowerBound(sorted.get(i));
            band.setUpperBound(i + 1 < sorted.size() ? sorted.get(i + 1) : null);
            band.setSort(i);
            post.getBands().add(band);
        }

        draft.getPosts().add(post);
        return draft;
    }

    private PaImportDraft.ItemDraft item(String code, String name, String weight,
                                         int raterLeader, int rater360,
                                         PaImportDraft.LevelDraft... levels) {
        return item(code, name, weight, raterLeader, rater360, null, levels);
    }

    private PaImportDraft.ItemDraft item(String code, String name, String weight,
                                         int raterLeader, int rater360, String raterText,
                                         PaImportDraft.LevelDraft... levels) {
        PaImportDraft.ItemDraft item = new PaImportDraft.ItemDraft();
        item.setItemCode(code);
        item.setItemName(name);
        item.setWeight(new BigDecimal(weight));
        item.setRaterLeader(raterLeader);
        item.setRater360(rater360);
        item.setRaterText(raterText);
        item.setSort(0);
        item.setSourceRow(4);
        for (int i = 0; i < levels.length; i++) {
            levels[i].setSort(i);
            item.getLevels().add(levels[i]);
        }
        return item;
    }

    private PaImportDraft.LevelDraft level(String name, String score) {
        PaImportDraft.LevelDraft lv = new PaImportDraft.LevelDraft();
        lv.setLevelName(name);
        lv.setScore(new BigDecimal(score));
        return lv;
    }
}
