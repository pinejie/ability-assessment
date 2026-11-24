package com.company.ability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.ability.config.PaImportAiProperties;
import com.company.ability.config.PaImportAsyncConfig;
import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaExcelRaw;
import com.company.ability.dto.PaImportAnswer;
import com.company.ability.dto.PaImportColumnMap;
import com.company.ability.dto.PaImportDraft;
import com.company.ability.dto.PaImportOverview;
import com.company.ability.dto.PaImportPlan;
import com.company.ability.dto.PaImportPreview;
import com.company.ability.dto.PaImportQuestion;
import com.company.ability.dto.PaImportRound;
import com.company.ability.dto.PaImportSessionVO;
import com.company.ability.dto.PaImportStructure;
import com.company.ability.entity.PaImportSession;
import com.company.ability.entity.PaImportStatus;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.PaImportSessionMapper;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 能力结构导入的编排：会话状态机 + 异步任务 + 多轮确认。
 *
 * <p><b>一件事一段代码，可中断可重入。</b>整个流程是：</p>
 * <pre>
 * upload  → PARSING（POI 读表）→ CHECKING（模型总览）→ SELECTING（用户勾 sheet）
 *            ↓ 用户确认 sheet
 *          CHECKING（逐张识别 + 装配 + 校验）→ NEEDS_CONFIRM（有问题）/ READY（没问题）
 *            ↓ 用户回答（可多轮）→ 校验回答、重算，回到 NEEDS_CONFIRM 或 READY
 *            ↓ 用户改计划（覆盖策略/封存报告/进行中任务）→ 重算预览
 *          READY → APPLYING（一个事务写库）→ DONE
 * </pre>
 *
 * <p><b>为什么每个动作都是「先返回、再轮询」</b>：模型单次调用实测 18~25 秒，
 * 一张表一次；5 张表就是两分钟。让一个 HTTP 请求挂两分钟，中间任何一个代理超时断开，
 * 后端还在跑、前端已经认为失败 —— 用户重试一次就有两个任务在写同一个岗位。
 * 所以每个入口只做「置状态 + 投任务 + 立刻返回」，前端在 1.5 秒粒度上轮询状态。</p>
 *
 * <p><b>会话持久化在 {@code uf_pa_import_session}</b>（sql/55、56），不是内存：
 * 需求是「切到别的页面再切回来还要看到」，进程重启也不能丢（人资报表那边已经验证过
 * 这套「任务态落库 + 前端轮询 + 启动兜底」是够用的）。</p>
 */
@Slf4j
@Service
public class PaImportService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 单个能力项至少 2 档（与 PaModelServiceImpl.MIN_LEVELS / PaImportQuestionBuilder 同口径） */
    private static final int MIN_LEVELS = 2;

    /** 等级槽位后缀。拒收回答与「能不能靠对话解决」共用同一判定，避免两处各写各的 */
    private static final String SLOT_LEVELS_SUFFIX = ".levels";

    private final PaImportSessionMapper sessionMapper;
    private final PaExcelReader excelReader;
    private final PaImportStructureAi structureAi;
    private final PaImportDraftAssembler assembler;
    private final PaImportQuestionBuilder questionBuilder;
    private final PaImportDialogAi dialogAi;
    private final PaImportValidator validator;
    private final PaImportPlanner planner;
    private final PaImportApplier applier;
    private final PaImportAiProperties aiProps;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final Executor executor;

    public PaImportService(PaImportSessionMapper sessionMapper, PaExcelReader excelReader,
                           PaImportStructureAi structureAi, PaImportDraftAssembler assembler,
                           PaImportQuestionBuilder questionBuilder, PaImportDialogAi dialogAi,
                           PaImportValidator validator, PaImportPlanner planner, PaImportApplier applier,
                           PaImportAiProperties aiProps, ObjectMapper json, JdbcTemplate jdbc,
                           @Qualifier(PaImportAsyncConfig.EXECUTOR) Executor executor) {
        this.sessionMapper = sessionMapper;
        this.excelReader = excelReader;
        this.structureAi = structureAi;
        this.assembler = assembler;
        this.questionBuilder = questionBuilder;
        this.dialogAi = dialogAi;
        this.validator = validator;
        this.planner = planner;
        this.applier = applier;
        this.aiProps = aiProps;
        this.json = json;
        this.jdbc = jdbc;
        this.executor = executor;
    }

    // ================================================================ 会话生命周期

    /**
     * 上传并解析。
     *
     * <p>文件立刻读完并落会话表（payload），后续所有步骤都基于会话里的 payload ——
     * 用户回答几轮问题之后再点导入，原文件早就随请求流关闭了。</p>
     *
     * @param file       Excel 文件（.xlsx / .xls）
     * @param overwrite  上一次会话还没结束时是否覆盖它
     */
    @Transactional(rollbackFor = Exception.class)
    public String upload(MultipartFile file, boolean overwrite) {
        LoginUser user = requireLogin();
        validateFile(file);

        // 上一份未完成的会话：默认拒绝，避免用户以为在续跑、其实起了两个任务写同一个岗位
        PaImportSession running = findActive(user);
        if (running != null && PaImportStatus.isBusy(running.getStatus())) {
            throw new BusinessException("上一次导入还在处理中（" + statusText(running.getStatus())
                    + "），请等它结束或刷新页面查看结果");
        }
        if (running != null && overwrite) {
            sessionMapper.deleteById(running.getId());
            log.info("用户选择丢弃旧会话 sessionKey={}（status={}）", running.getSessionKey(), running.getStatus());
        }

        PaExcelRaw raw;
        try (InputStream in = file.getInputStream()) {
            raw = excelReader.read(in, file.getOriginalFilename(), file.getSize());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("读取 Excel 失败：" + e.getMessage());
        }

        PaImportSession session = new PaImportSession();
        session.setSessionKey(UUID.randomUUID().toString());
        session.setDimensionId(DimensionContext.getOrDefault());
        session.setResourceId(user.resourceId());
        session.setFileName(raw.getFileName());
        session.setFileSize(raw.getFileSize());
        session.setStatus(PaImportStatus.PARSING);
        session.setStage("已读取 " + raw.getSheets().size() + " 个工作表，正在识别结构");
        session.setProgress(10);
        session.setRoundNo(0);
        session.setPayload(writeJson(raw));
        session.setCreateBy(user.resourceId());
        session.setExpireTime(expireAt());
        sessionMapper.insert(session);

        String key = session.getSessionKey();
        executor.execute(PaImportAsyncConfig.wrap(() -> runOverview(key)));
        log.info("导入会话创建 sessionKey={}, 文件={}, sheet 数={}",
                key, raw.getFileName(), raw.getSheets().size());
        return key;
    }

    /**
     * 当前用户的最新会话（进页面自动恢复）。
     *
     * @return 没有会话返回 null
     */
    public PaImportSessionVO current() {
        LoginUser user = requireLogin();
        PaImportSession session = findActive(user);
        if (session == null) {
            return null;
        }
        if (isExpired(session) && !PaImportStatus.EXPIRED.equals(session.getStatus())) {
            markExpired(session);
            session.setStatus(PaImportStatus.EXPIRED);
        }
        return toVO(session);
    }

    /** 轮询单个会话 */
    public PaImportSessionVO state(String sessionKey) {
        return toVO(requireOwned(sessionKey));
    }

    /** 放弃会话（用户主动关掉） */
    public void abandon(String sessionKey) {
        PaImportSession session = requireOwned(sessionKey);
        if (PaImportStatus.isBusy(session.getStatus())) {
            throw new BusinessException("会话正在处理中，请等它结束后再关闭");
        }
        sessionMapper.deleteById(session.getId());
        log.info("用户放弃导入会话 sessionKey={}", sessionKey);
    }

    // ================================================================ 步骤一：选 sheet

    /**
     * 确认要导入哪些 sheet。
     *
     * <p>客户这份文件有 25 张表。全导就是一次建 25 个岗位，而实际一次只想导 2 个 ——
     * 这个选择必须让用户做，不能由模型替用户决定。</p>
     */
    public void selectSheets(String sessionKey, PaImportAnswer answer) {
        PaImportSession session = requireOwned(sessionKey);
        requireStatus(session, PaImportStatus.SELECTING);

        List<String> chosen = answer.getSelectedSheets() == null ? List.of() : answer.getSelectedSheets();
        if (chosen.isEmpty() && !answer.isConfirmNone()) {
            throw new BusinessException("请至少选择一张要导入的工作表");
        }
        if (chosen.isEmpty()) {
            update(session, s -> {
                s.setStatus(PaImportStatus.FAILED);
                s.setError("你确认了这些工作表都不导入，本次导入已结束");
                s.setStage(null);
            });
            return;
        }

        // 校验勾选的 sheet 确实存在（前端可能拿旧总览提交）
        PaExcelRaw raw = readPayload(session);
        Set<String> existing = new LinkedHashSet<>();
        for (PaExcelRaw.Sheet s : raw.getSheets()) {
            existing.add(s.getName());
        }
        List<String> unknown = chosen.stream().filter(n -> !existing.contains(n)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException("这些工作表在文件里不存在：" + String.join("、", unknown));
        }

        update(session, s -> {
            s.setStatus(PaImportStatus.CHECKING);
            s.setStage("正在识别 " + chosen.size() + " 张工作表的列结构");
            s.setProgress(25);
            s.setUserPlan(writeJson(planOf(s, p -> p.setSelectedSheets(new ArrayList<>(chosen)))));
        });
        executor.execute(PaImportAsyncConfig.wrap(() -> runDetect(sessionKey, chosen)));
    }

    // ================================================================ 步骤二：回答确认

    /**
     * 提交一轮回答。
     *
     * <p>所有问题一次问完（用户明确要求），所以这里一次收全部回答，能解多少解多少，
     * 解不掉的和新发现的问题合成下一轮。轮次超过安全阀转 MANUAL。</p>
     */
    public void answer(String sessionKey, PaImportAnswer answer) {
        PaImportSession session = requireOwned(sessionKey);
        requireStatus(session, PaImportStatus.NEEDS_CONFIRM);

        update(session, s -> {
            s.setStatus(PaImportStatus.CHECKING);
            s.setStage("正在核对你的回答");
            s.setProgress(55);
        });
        executor.execute(PaImportAsyncConfig.wrap(() -> runAnswer(sessionKey, answer)));
    }

    /**
     * 改导入计划（覆盖策略 / 重算封存报告 / 撤回进行中任务）。
     *
     * <p>每次改动都重算预览 —— 用户勾一个「重新生成已封存报告」，预览里的影响面必须立刻变。
     * 预览不能只在前端拼，否则执行时按另一套口径走，用户看到的和实际发生的不一致。</p>
     */
    public PaImportSessionVO updatePlan(String sessionKey, PaImportPlan plan) {
        PaImportSession session = requireOwned(sessionKey);
        if (PaImportStatus.isTerminal(session.getStatus()) || PaImportStatus.isBusy(session.getStatus())) {
            throw new BusinessException("当前状态（" + statusText(session.getStatus()) + "）不能调整导入计划");
        }
        PaImportPlan merged = mergePlan(readPlan(session), plan);
        update(session, s -> s.setUserPlan(writeJson(merged)));

        // READY 才有预览可重算；NEEDS_CONFIRM 阶段先只存计划，等回答完再算
        if (PaImportStatus.READY.equals(session.getStatus())) {
            PaImportDraft draft = readDraft(session);
            PaImportPreview preview = planner.preview(draft, merged);
            update(session, s -> s.setPreview(writeJson(preview)));
        }
        return toVO(requireOwned(sessionKey));
    }

    // ================================================================ 步骤三：写库

    /** 点「确认导入」 */
    public void apply(String sessionKey) {
        PaImportSession session = requireOwned(sessionKey);
        requireStatus(session, PaImportStatus.READY);

        PaImportPlan plan = readPlan(session);
        if (!plan.isOverwrite()) {
            // 跳过模式下，如果所有岗位都已存在，就没必要走写库流程
            PaImportPreview preview = readPreview(session);
            if (preview != null && preview.getPosts().stream().allMatch(p -> "SKIP".equals(p.getAction()))) {
                throw new BusinessException("按当前设置所有岗位都会被跳过，没有可导入的内容");
            }
        }
        update(session, s -> {
            s.setStatus(PaImportStatus.APPLYING);
            s.setStage("正在写入能力模型");
            s.setProgress(70);
        });
        executor.execute(PaImportAsyncConfig.wrap(() -> runApply(sessionKey)));
    }

    // ================================================================ 异步任务

    /** 模型总览：每张 sheet 是不是岗位模型 */
    void runOverview(String sessionKey) {
        PaImportSession session = loadByKey(sessionKey);
        if (session == null) {
            return;
        }
        try {
            PaExcelRaw raw = readPayload(session);
            PaImportOverview overview = structureAi.overview(raw);
            if (overview == null) {
                // 模型不可用/失败：降级成「全部 sheet 交用户手工勾选」
                overview = fallbackOverview(raw);
            }
            enrichOverview(overview, raw);
            PaImportOverview finalOverview = overview;

            update(session, s -> {
                s.setOverview(writeJson(finalOverview));
                s.setStatus(PaImportStatus.SELECTING);
                s.setStage("请确认要导入哪些工作表");
                s.setProgress(20);
                // 不预选任何工作表。以前这里会替用户勾上模型判 IMPORT 的前 5 张，
                // 结果「必须让用户自己选 sheet」这条防线形同虚设 —— 用户什么都不点、
                // 直接按「识别选中的工作表」，25 张表里的那 5 张就被建成 5 个岗位。
                // 现在保持空选，由用户明确勾要导入哪几张。
            });
        } catch (Exception e) {
            fail(session, "识别文件结构失败：" + e.getMessage(), e);
        }
    }

    /**
     * 识别结构 + 装配 + 校验。
     *
     * <p><b>两条识别路径，先列映射后逐格</b>：</p>
     * <ol>
     *   <li>{@code detectColumns} —— 一次调用拿到所有表的列坐标，模型不抄内容。
     *       25 张表也只有十几行/表，不会撞 max_tokens。</li>
     *   <li>模型说某张表排版认不出来（{@code layout=OTHER}）的，对<b>那几张逐张</b>走老的逐格识别。
     *       单张表的输出量远在额度内。</li>
     * </ol>
     *
     * <p>为什么不整份文件直接走逐格：实测 25 张表会被截断（返回 null），
     * 用户看到「模型没能读懂这份文件的结构」，而其实只是说不完。</p>
     */
    void runDetect(String sessionKey, List<String> sheets) {
        PaImportSession session = loadByKey(sessionKey);
        if (session == null) {
            return;
        }
        try {
            PaExcelRaw raw = readPayload(session);
            PaExcelRaw subset = subsetOf(raw, sheets);
            updateStage(session, "正在识别 " + sheets.size() + " 张表的列结构", 30);

            PaImportColumnMap columnMap = structureAi.detectColumns(subset);
            PaImportDraft draft;
            List<String> failedSheets = new ArrayList<>();

            if (columnMap != null) {
                draft = assembler.assembleColumns(subset, columnMap);
                // 列映射表达不了的排版（一项跨多行 / 横排）：逐张走逐格识别
                List<String> others = otherLayoutSheets(columnMap);
                for (int i = 0; i < others.size(); i++) {
                    String name = others.get(i);
                    updateStage(session, "正在逐格识别「" + name + "」（这张表排版不规则，"
                            + (i + 1) + "/" + others.size() + "）", 35 + i * 5);
                    if (!detectSingleInto(draft, subset, name)) {
                        failedSheets.add(name);
                    }
                }
            } else {
                // 列映射没拿到（模型不可用或返回不可解析）：逐张走逐格。
                // 逐张而不是整份再试一次 —— 整份正是撞额度的那条路，重走一遍只会再失败一次
                log.warn("会话 {} 列映射不可用，降级为逐张逐格识别（{} 张）", sessionKey, sheets.size());
                draft = new PaImportDraft();
                for (int i = 0; i < sheets.size(); i++) {
                    String name = sheets.get(i);
                    updateStage(session, "正在逐格识别「" + name + "」（" + (i + 1) + "/" + sheets.size() + "）",
                            30 + i);
                    if (!detectSingleInto(draft, subset, name)) {
                        failedSheets.add(name);
                    }
                }
            }

            if (draft.getPosts().isEmpty()) {
                String detail = failedSheets.isEmpty() ? ""
                        : "（这些工作表没能识别："
                        + String.join("、", failedSheets.size() > 3
                        ? failedSheets.subList(0, 3) : failedSheets)
                        + (failedSheets.size() > 3 ? " 等 " + failedSheets.size() + " 张" : "") + "）";
                fail(session, "这些工作表里没有识别出任何岗位能力模型" + detail
                        + "。可以把模板发给我们核对，或改用手工在「岗位能力模型」里维护。", null);
                return;
            }

            updateStage(session, "正在校验读出来的内容", 60);
            Map<String, String> resolved = readResolved(session);
            // 文件里写了部门名的，先按组织树解析成 id：只有文字没有 id 是没法建岗位的
            // （唯一键、部门名快照都要 id）。解析不出唯一匹配就留给问题构造器去问人。
            resolveFileDepartments(draft, resolved);
            List<PaImportQuestion> questions = new ArrayList<>(questionBuilder.build(draft, resolved));
            // 同部门同名岗位冲突也要问：覆盖还是跳过，是用户的决定
            questions.addAll(collisionQuestions(draft, resolved));
            // 有表识别失败但其它成功：说明白哪几张没进来，别让用户以为全导进去了
            appendSheetFailures(questions, failedSheets);
            // 草稿必须先落库：finishRound 会读会话里的 plan / dialog，下游还要用这份草稿
            saveDraft(session, draft);
            finishRound(session, draft, questions, resolved, null);
        } catch (BusinessException e) {
            fail(session, e.getMessage(), e);
        } catch (Exception e) {
            fail(session, "识别结构失败：" + e.getMessage(), e);
        }
    }

    /**
     * 对单张表走逐格识别并并入草稿。
     *
     * @return 这张表识别成功返回 true；模型没给出结构（不可用/被截断/解析失败）返回 false
     */
    private boolean detectSingleInto(PaImportDraft draft, PaExcelRaw subset, String sheetName) {
        try {
            PaExcelRaw one = subsetOf(subset, List.of(sheetName));
            PaImportStructure structure = structureAi.detect(one);
            if (structure == null) {
                log.warn("工作表「{}」逐格识别失败", sheetName);
                return false;
            }
            PaImportDraft part = assembler.assemble(one, structure);
            if (part.getPosts().isEmpty()) {
                log.warn("工作表「{}」逐格识别没产出岗位", sheetName);
                return false;
            }
            assembler.mergeInto(draft, part);
            return true;
        } catch (Exception e) {
            log.warn("工作表「{}」逐格识别异常：{}", sheetName, e.getMessage());
            return false;
        }
    }

    /** 模型标了 layout=OTHER（或没给 layout）的表 */
    private static List<String> otherLayoutSheets(PaImportColumnMap columnMap) {
        List<String> out = new ArrayList<>();
        for (PaImportColumnMap.SheetMap sm : columnMap.getSheets()) {
            if (!"IMPORT".equalsIgnoreCase(sm.getAction())) {
                continue;
            }
            String layout = sm.getLayout();
            if (layout == null || !"ROW_PER_ITEM".equalsIgnoreCase(layout.trim())) {
                out.add(sm.getSheetName());
            }
        }
        return out;
    }

    /** 有表没识别出来：补一条不阻断的提醒，用户看得到、也不挡着导入其它表 */
    private static void appendSheetFailures(List<PaImportQuestion> questions, List<String> failed) {
        if (failed.isEmpty()) {
            return;
        }
        PaImportQuestion q = new PaImportQuestion();
        q.setType(PaImportQuestion.SUSPECT);
        q.setSlot("sheet.failed." + Math.abs(String.join(",", failed).hashCode()));
        q.setTopic("这些工作表没能识别出内容：" + String.join("、", failed)
                + "。可能是排版太特殊，也可能整张表就是说明页。已识别的表不受影响。");
        q.setQuestion(q.getTopic());
        q.setBlocking(false);
        questions.add(q);
    }

    /** 核对回答 → 落值 → 重算 */
    void runAnswer(String sessionKey, PaImportAnswer answer) {
        PaImportSession session = loadByKey(sessionKey);
        if (session == null) {
            return;
        }
        try {
            PaImportDraft draft = readDraft(session);
            List<PaImportQuestion> asked = readQuestions(session);
            Map<String, String> resolved = readResolved(session);

            // 部门：前端给了 id 就直接定（比名字匹配可靠得多）
            boolean deptDecided = false;
            if (answer.getDepartmentId() != null) {
                Map<String, String> deptInfo = resolveDepartment(answer.getDepartmentId());
                if (deptInfo == null) {
                    throw new BusinessException("部门不存在（id=" + answer.getDepartmentId() + "），请重新选择");
                }
                fillDepartment(draft, resolved, Long.valueOf(deptInfo.get("id")), deptInfo.get("name"));
                deptDecided = true;
            } else if (answer.isGenericDepartment()) {
                fillDepartment(draft, resolved, null, null);
                deptDecided = true;
            }

            // 页面上点选的部门/通用是最终答案，不能再被 values 里的 dept 覆盖。
            // 那条路径以前踩过：前端曾把问题建议值预填进 answers['dept']（该槽位在页面上
            // 没有输入框，用户看不见），提交时和真正选的部门一起上来，谁后写谁赢 ——
            // 结果选了部门反而落成「全公司通用」。前端已不再预填，这里再拦一层。
            Map<String, String> structured = answer.getValues();
            if (deptDecided && structured != null && !structured.isEmpty()) {
                structured = new LinkedHashMap<>(structured);
                structured.remove(PaImportQuestionBuilder.slotDept());
            }

            PaImportRound round = dialogAi.extract(asked, readHistory(session),
                    structured, answer.getText());
            if (round.getOutOfScope() != null && !round.getOutOfScope().isBlank()) {
                // 越界不算一轮：不推进 roundNo，把提示回给用户重答
                List<Map<String, Object>> dialog = readDialog(session);
                dialog.add(entry("ANSWER", answer.getText(), null));
                dialog.add(entry("NOTE", round.getOutOfScope(), null));
                update(session, s -> {
                    s.setDialog(writeJson(dialog));
                    s.setStatus(PaImportStatus.NEEDS_CONFIRM);
                    s.setError(round.getOutOfScope());
                    s.setStage("你的回答似乎与本次导入无关，请重新确认");
                });
                return;
            }

            // 落值 + 校验：模型抽出来的值未必合法（部门名对不上、权重不是数字）
            Map<String, String> problems = applyAnswers(draft, resolved, round.getValues(), asked);
            List<Map<String, Object>> dialog = readDialog(session);
            dialog.add(entry("ANSWER", answer.getText(), null));
            for (Map.Entry<String, String> e : round.getValues().entrySet()) {
                String problem = problems.get(e.getKey());
                dialog.add(entry(problem == null ? "RESOLVED" : "NOTE",
                        (problem == null ? "已确认：" : "没能采用：") + e.getValue()
                                + (problem == null ? "" : "（" + problem + "）"), e.getKey()));
            }
            if (round.getReply() != null && !round.getReply().isBlank()) {
                dialog.add(entry("NOTE", round.getReply(), null));
            }

            Map<String, String> finalResolved = resolved;
            List<Map<String, Object>> finalDialog = dialog;
            update(session, s -> {
                s.setDialog(writeJson(finalDialog));
                s.setUserPlan(writeJson(planOf(s, p -> p.setResolved(finalResolved))));
                s.setRoundNo((s.getRoundNo() == null ? 0 : s.getRoundNo()) + 1);
                s.setError(null);
            });
            // 草稿必须落库：applyAnswers 把用户确认的部门/权重/评级主体写进了 draft，
            // 不存的话下一轮和最终写库都会重新从库里读回一份没改过的草稿
            saveDraft(session, draft);

            List<PaImportQuestion> questions = new ArrayList<>(questionBuilder.build(draft, resolved));
            questions.addAll(collisionQuestions(draft, resolved));
            // 模型说「还差这几项」且代码确实还没解 → 补成追问
            appendFollowUps(questions, round, asked);
            finishRound(session, draft, questions, resolved, null);
        } catch (BusinessException e) {
            fail(session, e.getMessage(), e);
        } catch (Exception e) {
            fail(session, "核对回答失败：" + e.getMessage(), e);
        }
    }

    /**
     * 这个槽位能不能靠一次对话回答解决。
     *
     * <p>等级类不能：{@code applyAnswers} 会明确拒收它（档名/描述/分值只能来自文件）。
     * 沿用 {@code endsWith(".levels")} 这一条判定，与拒收处同一口径 ——
     * 两处要是各写各的，就会出现「这里说能问、那边拒收」的反复追问。</p>
     */
    private static boolean isDialogSolvable(String slot) {
        return slot == null || !slot.endsWith(SLOT_LEVELS_SUFFIX);
    }

    /** 写库 */
    void runApply(String sessionKey) {
        PaImportSession session = loadByKey(sessionKey);
        if (session == null) {
            return;
        }
        try {
            PaImportDraft draft = readDraft(session);
            PaImportPlan plan = readPlan(session);
            // 执行前重算预览：用户可能在上一步改了计划，而预览是按旧计划算的
            PaImportPreview preview = planner.preview(draft, plan);
            if (!preview.getBlockers().isEmpty()) {
                throw new BusinessException("还有未解决的问题，不能导入："
                        + preview.getBlockers().get(0).getQuestion());
            }
            updateStage(session, "正在写入能力模型", 75);

            PaImportApplier.Result result = applier.apply(draft, plan, preview);
            update(session, s -> {
                s.setResult(writeJson(result));
                s.setPreview(writeJson(preview));
                s.setStatus(PaImportStatus.DONE);
                s.setStage("导入完成");
                s.setProgress(100);
                s.setError(null);
            });
        } catch (BusinessException e) {
            fail(session, e.getMessage(), e);
        } catch (Exception e) {
            fail(session, "写入失败：" + e.getMessage() + "（已全部回滚，库里没有任何改动）", e);
        }
    }

    // ================================================================ 轮次收尾

    /**
     * 一轮的收尾：把问题润色一遍，决定进 NEEDS_CONFIRM 还是 READY。
     *
     * <p>润色失败不影响流程（问题本来就带代码文案），只是生硬一点。</p>
     *
     * <p>包级可见是为了让测试能直接验「只剩不可对话解决的问题时直接转人工」：
     * 走 {@code runAnswer} 要先过模型抽值，单测里跑不到这段。</p>
     */
    void finishRound(PaImportSession session, PaImportDraft draft,
                             List<PaImportQuestion> questions, Map<String, String> resolved,
                             String intro) {
        List<PaImportQuestion> blocking = questions.stream()
                .filter(PaImportQuestion::isBlocking).toList();

        if (!blocking.isEmpty()) {
            // 剩余问题如果全都没法靠对话解决（目前只有等级类），继续追问就是让人白答几轮
            // 再撞上限。直接转人工，并把「该去改表格」说清楚。
            boolean anySolvable = blocking.stream().anyMatch(q -> isDialogSolvable(q.getSlot()));
            if (!anySolvable) {
                update(session, s -> {
                    s.setStatus(PaImportStatus.MANUAL);
                    s.setStage(blocking.size() + " 个等级问题只能改表格，需要人工处理");
                    s.setPending(writeJson(questions));
                    s.setError("这份文件里有 " + blocking.size()
                            + " 个能力项的等级不足 " + MIN_LEVELS + " 档。档位的名称、描述、分值只能来自你的表格，"
                            + "对话里补不出来 —— 请回表格把这些能力项补到至少 " + MIN_LEVELS
                            + " 档后重新上传，或到「岗位能力模型」页面手工维护。");
                    s.setProgress(50);
                });
                log.warn("会话 {} 只剩不可对话解决的问题，直接转人工：{} 条",
                        session.getSessionKey(), blocking.size());
                return;
            }

            int round = session.getRoundNo() == null ? 0 : session.getRoundNo();
            if (round >= aiProps.getMaxRounds()) {
                // 安全阀：连续 N 轮还有解决不了的，说明这份文件不适合自动识别，转人工
                update(session, s -> {
                    s.setStatus(PaImportStatus.MANUAL);
                    s.setStage("已追问 " + round + " 轮仍有问题未解决");
                    s.setPending(writeJson(questions));
                    s.setError("追问次数已达上限（" + aiProps.getMaxRounds() + " 轮）。"
                            + "剩下的问题靠对话解决不了，建议改用文件里的标准模板重做，"
                            + "或到「岗位能力模型」页面手工维护。");
                    s.setProgress(50);
                });
                log.warn("会话 {} 触发安全阀：{} 轮后仍有 {} 个阻断项", session.getSessionKey(), round, blocking.size());
                return;
            }

            PaImportDialogAi.Reworded reworded = dialogAi.reword(questions, postNames(draft));
            List<Map<String, Object>> dialog = readDialog(session);
            if (intro != null || !reworded.intro().isBlank()) {
                dialog.add(entry("NOTE", reworded.intro().isBlank() ? intro : reworded.intro(), null));
            }
            for (PaImportQuestion q : questions) {
                dialog.add(entry("QUESTION", q.getQuestion(), q.getSlot()));
            }

            update(session, s -> {
                s.setStatus(PaImportStatus.NEEDS_CONFIRM);
                s.setStage(questions.size() + " 个地方需要你确认");
                s.setPending(writeJson(questions));
                s.setDialog(writeJson(dialog));
                s.setPreview(null);
                s.setError(null);
                s.setProgress(60);
            });
            return;
        }

        // 没有阻断项了：算预览，进 READY
        PaImportPlan plan = readPlan(session);
        PaImportPreview preview = planner.preview(draft, plan);
        if (!preview.getBlockers().isEmpty()) {
            // 提醒类问题不该成阻断项，兜一层防止校验器与问题构造器口径漂移
            List<Map<String, Object>> dialog = readDialog(session);
            for (PaImportQuestion pb : preview.getBlockers()) {
                dialog.add(entry("QUESTION", pb.getQuestion(), pb.getSlot()));
            }
            update(session, s -> {
                s.setStatus(PaImportStatus.NEEDS_CONFIRM);
                s.setStage(preview.getBlockers().size() + " 个地方需要你确认");
                s.setPending(writeJson(preview.getBlockers()));
                s.setDialog(writeJson(dialog));
                s.setProgress(60);
            });
            return;
        }

        List<Map<String, Object>> dialog = readDialog(session);
        dialog.add(entry("NOTE", "确认完成，下面是导入预览。请核对之后再点「确认导入」。", null));
        update(session, s -> {
            s.setStatus(PaImportStatus.READY);
            s.setStage("请核对导入预览");
            s.setPending(null);
            s.setPreview(writeJson(preview));
            s.setDialog(writeJson(dialog));
            s.setError(null);
            s.setProgress(65);
        });
        log.info("会话 {} 进入 READY：{} 个岗位待导入", session.getSessionKey(), preview.getPosts().size());
    }

    // ================================================================ 回答落值

    /**
     * 把确认值写进草稿。
     *
     * <p>模型抽出来的值必须在这里再校验一遍：它可能把部门写成「质量部」而组织树里有三个
     * 同名部门，也可能把「98%」写成「差不多」。</p>
     *
     * <p>包级可见是为了让测试能直接验「回答→草稿」这一步：{@code runAnswer} 还要过模型抽值，
     * 单测里跑不到这段逻辑，而部门回填正是最容易漏的一步。</p>
     *
     * @return slot → 不能采用的原因（能采用的返回空 map）
     */
    Map<String, String> applyAnswers(PaImportDraft draft, Map<String, String> resolved,
                                     Map<String, String> values, List<PaImportQuestion> asked) {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String slot = e.getKey();
            String value = e.getValue();
            try {
                if (PaImportQuestionBuilder.slotDept().equals(slot)) {
                    // 「全公司通用」不是部门名，不能拿去组织树里查 —— 先拦下来
                    if (PaImportQuestionBuilder.isGenericDepartment(value)) {
                        fillDepartment(draft, resolved, null, null);
                        continue;
                    }
                    Map<String, String> dept = findDepartment(value);
                    if (dept == null) {
                        problems.put(slot, "组织树里找不到唯一匹配的部门，请在页面上直接选择"
                                + "（如果这些岗位全公司通用，填「" + PaImportQuestionBuilder.GENERIC_DEPARTMENT + "」）");
                        continue;
                    }
                    fillDepartment(draft, resolved, Long.valueOf(dept.get("id")), dept.get("name"));
                    continue;
                }
                if (slot != null && slot.endsWith(".weight")) {
                    // 权重类回答：只接受「保持原样」或「摊平」，数值由代码算
                    String v = value.trim();
                    if (v.contains("保持") || v.contains("就按") || v.contains("原样") || v.contains("98")) {
                        resolved.put(slot, "keep");
                    } else if (v.contains("摊平") || v.contains("归到") || v.contains("换算")) {
                        resolved.put(slot, "normalize");
                    } else {
                        problems.put(slot, "没看懂这个处理方式，请从候选项里选一个");
                        continue;
                    }
                    continue;
                }
                if (slot != null && slot.endsWith(SLOT_LEVELS_SUFFIX)) {
                    // 等级类不能靠回答解决：档名/描述/分值只能来自文件，对话里给不出这些。
                    // 这里明确拒绝，并告诉人该去改什么 —— 静默记成「已解决」正是原来那个洞：
                    // resolved 里有了这个 slot，问题消失，1 档能力项原样写进库。
                    problems.put(slot, "等级只能在表格里补（对话里改不了档位），请补到至少 "
                            + MIN_LEVELS + " 档后重新上传");
                    continue;
                }
                resolved.put(slot, value);
            } catch (Exception ex) {
                problems.put(slot, ex.getMessage());
            }
        }
        return problems;
    }

    /** 部门名 → 唯一部门；重名或找不到返回 null（让人去选，不猜） */
    private Map<String, String> findDepartment(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        // 已经是「全公司通用」的不要再往下查：包含匹配会把「通用」匹配到某个叫「通用设备部」的部门上，
        // 于是用户明明说的是不绑部门，导出来的岗位却挂在了通用设备部下
        if (PaImportQuestionBuilder.isGenericDepartment(name)) {
            return null;
        }
        String clean = name.replaceAll("^(部门|所属部门)[:：]?", "").replaceAll("[#＃].*$", "").trim();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, departmentname FROM HrmDepartment" +
                        " WHERE ISNULL(canceled, 0) = 0 AND departmentname = ?", clean);
        if (rows.size() == 1) {
            return Map.of("id", String.valueOf(rows.get(0).get("id")),
                    "name", String.valueOf(rows.get(0).get("departmentname")));
        }
        if (rows.isEmpty()) {
            // 允许按「包含」唯一匹配：「质量」→「质量管理部」
            List<Map<String, Object>> fuzzy = jdbc.queryForList(
                    "SELECT id, departmentname FROM HrmDepartment" +
                            " WHERE ISNULL(canceled, 0) = 0 AND departmentname LIKE ?", "%" + clean + "%");
            if (fuzzy.size() == 1) {
                return Map.of("id", String.valueOf(fuzzy.get(0).get("id")),
                        "name", String.valueOf(fuzzy.get(0).get("departmentname")));
            }
        }
        return null;
    }

    private Map<String, String> resolveDepartment(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, departmentname FROM HrmDepartment WHERE id = ? AND ISNULL(canceled, 0) = 0", id);
        if (rows.isEmpty()) {
            return null;
        }
        return Map.of("id", String.valueOf(rows.get(0).get("id")),
                "name", String.valueOf(rows.get(0).get("departmentname")));
    }

    /**
     * 把文件里读到的部门名解析成部门 id。
     *
     * <p>装配器只负责取原文，读到的是「质量部」这种字符串；而岗位的唯一键和部门名快照都要 id。
     * 不解析的话，文件写了部门的表会因为「没 id」被当成没写部门去问人 ——
     * 用户明明在表里写了，却被问一遍，等于白写。</p>
     *
     * <p>只认唯一匹配：重名部门（不同公司都叫质量部）不猜，留在问题里让人选。</p>
     *
     * <p>包级可见是为了让测试能直接验「文件里写的部门能不能落成 id」，
     * 走 {@code runDetect} 要过一遍模型识别，单测里跑不到。</p>
     */
    void resolveFileDepartments(PaImportDraft draft, Map<String, String> resolved) {
        if (resolved.containsKey(PaImportQuestionBuilder.slotDept())) {
            return; // 用户已经确认过了，别再改
        }
        // 一次导入只指定一个部门，所以取第一个带部门名的岗位去解析
        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            if (post.getDepartmentName() == null || post.getDepartmentName().isBlank()) {
                continue;
            }
            Map<String, String> dept = findDepartment(post.getDepartmentName());
            if (dept == null) {
                continue; // 重名或查不到：留给问题，让用户在页面上选
            }
            fillDepartment(draft, resolved, Long.valueOf(dept.get("id")), dept.get("name"));
            return;
        }
    }

    /**
     * 把用户确认的部门写进草稿与 resolved。
     *
     * <p>{@code deptId == null} 表示「全公司通用」。两件事必须一起做：</p>
     * <ol>
     *   <li>resolved 里留下 {@code dept} 这个 slot —— 否则问题构造器会一直重复问部门</li>
     *   <li>草稿里的每个岗位都写入（或清空）departmentId —— 只写 resolved 的话，
     *       问题消失了但岗位本身还是没部门，预览部门列是空的、写库时建成通用岗位</li>
     * </ol>
     *
     * <p>清空时显式 set null 而不是「不动它」：用户在上一轮可能先选了部门再改成通用，
     * 只跳过赋值会把上一轮的部门留着，导出来还是有部门。</p>
     */
    private void fillDepartment(PaImportDraft draft, Map<String, String> resolved,
                                Long deptId, String deptName) {
        if (deptId == null) {
            // 值带 # 后缀与选部门时同构（name#id），这里留下的是人可读的标记
            resolved.put(PaImportQuestionBuilder.slotDept(), PaImportQuestionBuilder.GENERIC_DEPARTMENT);
        } else {
            resolved.put(PaImportQuestionBuilder.slotDept(), deptName + "#" + deptId);
        }
        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            post.setDepartmentId(deptId);
            post.setDepartmentName(deptId == null ? null : deptName);
        }
    }

    // ================================================================ 冲突问题

    /** 岗位所属部门的展示文案：通用岗位写「全公司通用范围里」，别让页面出现 `部门「null」下` */
    private static String deptLabel(PaImportDraft.PostDraft post) {
        return post.getDepartmentId() == null
                ? "全公司通用范围里"
                : "部门「" + nz(post.getDepartmentName()) + "」下";
    }

    /**
     * 同部门同名岗位是否已存在 —— 这个代码能查，但「覆盖还是跳过」是用户的决定。
     *
     * <p>放在这里而不是 {@code PaImportQuestionBuilder} 里：那个类是纯函数（不查库），
     * 这是唯一需要查库的一类问题。</p>
     */
    private List<PaImportQuestion> collisionQuestions(PaImportDraft draft, Map<String, String> resolved) {
        List<PaImportQuestion> out = new ArrayList<>();
        PaImportPlan plan = new PaImportPlan();
        plan.setExisting(resolved.getOrDefault("existing", PaImportPlan.OVERWRITE));
        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            // 部门没确认就查不了冲突：null 有两种含义（还没答 / 已确认是通用岗位），
            // 靠 resolved 里的 dept slot 区分，不能直接看 departmentId 是不是空
            if (!resolved.containsKey(PaImportQuestionBuilder.slotDept())) {
                continue;
            }
            var existing = validator.findExistingPost(post.getDepartmentId(), post.getPostName());
            if (existing == null) {
                continue;
            }
            PaImportValidator.Diff diff = validator.diff(existing, post);
            PaImportQuestion q = new PaImportQuestion();
            q.setType(PaImportQuestion.COLLISION);
            q.setSlot("post." + nz(post.getSheetName()) + ".existing");
            q.setSheetName(post.getSheetName());
            q.setTopic(deptLabel(post) + "已经有岗位「" + post.getPostName() + "」，"
                    + (diff.hasDifference()
                    ? "内容有差异（改动 " + diff.changed.size() + " 项、新增 " + diff.added.size()
                    + " 项、移除 " + diff.removed.size() + " 项）"
                    : "内容完全一致")
                    + "。导入时怎么处理？");
            q.setQuestion(q.getTopic());
            q.setCandidates(List.of("覆盖（以文件为准）", "跳过（保留库里现状）"));
            // 不阻断：默认覆盖就是业务确认的口径，用户不回答也能导
            q.setBlocking(false);
            q.getContext().put("postId", existing.getId());
            q.getContext().put("hasDifference", diff.hasDifference());
            out.add(q);
        }
        return out;
    }

    // ================================================================ 状态读取 / 转换

    private PaImportSessionVO toVO(PaImportSession session) {
        PaImportSessionVO vo = new PaImportSessionVO();
        vo.setSessionKey(session.getSessionKey());
        vo.setStatus(session.getStatus());
        vo.setStatusText(statusText(session.getStatus()));
        vo.setStage(session.getStage());
        vo.setProgress(session.getProgress());
        vo.setRoundNo(session.getRoundNo());
        vo.setMaxRounds(aiProps.getMaxRounds());
        vo.setFileName(session.getFileName());
        vo.setFileSize(session.getFileSize());
        vo.setError(session.getError());
        vo.setCreateTime(session.getCreateTime());
        vo.setUpdateTime(session.getUpdateTime());
        vo.setExpireTime(session.getExpireTime());
        vo.setBusy(PaImportStatus.isBusy(session.getStatus()));

        vo.setOverview(readJson(session.getOverview(), PaImportOverview.class));
        PaImportPlan plan = readPlan(session);
        vo.setPlan(plan);
        vo.setSelectedSheets(plan.getSelectedSheets());

        if (PaImportStatus.NEEDS_CONFIRM.equals(session.getStatus())
                || PaImportStatus.MANUAL.equals(session.getStatus())) {
            List<PaImportQuestion> questions = readJsonList(session.getPending(), PaImportQuestion.class);
            vo.setQuestions(questions);
        }
        vo.setPreview(readJson(session.getPreview(), PaImportPreview.class));
        vo.setResult(readJsonAny(session.getResult()));
        vo.setDialog(readJsonList(session.getDialog(), PaImportSessionVO.DialogEntry.class));

        vo.setCanAnswer(PaImportStatus.NEEDS_CONFIRM.equals(session.getStatus()));
        vo.setCanApply(PaImportStatus.READY.equals(session.getStatus()));
        return vo;
    }

    private static String statusText(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case PaImportStatus.PARSING -> "正在读取文件";
            case PaImportStatus.CHECKING -> "正在识别结构";
            case PaImportStatus.SELECTING -> "等待选择工作表";
            case PaImportStatus.NEEDS_CONFIRM -> "等待你确认";
            case PaImportStatus.READY -> "可以导入";
            case PaImportStatus.APPLYING -> "正在写入";
            case PaImportStatus.DONE -> "导入完成";
            case PaImportStatus.FAILED -> "导入失败";
            case PaImportStatus.MANUAL -> "需要手工处理";
            case PaImportStatus.EXPIRED -> "会话已过期";
            default -> status;
        };
    }

    // ================================================================ 会话存取

    private LoginUser requireLogin() {
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null) {
            throw new BusinessException("请先登录");
        }
        return user;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要导入的 Excel 文件");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            throw new BusinessException("只支持 .xlsx / .xls 文件，当前是：" + file.getOriginalFilename());
        }
        if (file.getSize() > 20L * 1024 * 1024) {
            throw new BusinessException("文件超过 20MB，请拆分后再导入");
        }
    }

    /** 当前用户最近一个未终态的会话 */
    private PaImportSession findActive(LoginUser user) {
        List<PaImportSession> list = sessionMapper.selectList(new LambdaQueryWrapper<PaImportSession>()
                .eq(PaImportSession::getResourceId, user.resourceId())
                .orderByDesc(PaImportSession::getUpdateTime)
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 取会话并校验归属：会话里带整份文件内容，不能跨人可见 */
    private PaImportSession requireOwned(String sessionKey) {
        LoginUser user = requireLogin();
        PaImportSession session = loadByKey(sessionKey);
        if (session == null || !Objects.equals(session.getResourceId(), user.resourceId())) {
            throw new BusinessException("导入会话不存在或已过期");
        }
        if (isExpired(session) && !PaImportStatus.isTerminal(session.getStatus())) {
            markExpired(session);
            throw new BusinessException("导入会话已过期（超过 30 分钟无操作），请重新上传文件");
        }
        return session;
    }

    private PaImportSession loadByKey(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return null;
        }
        List<PaImportSession> list = sessionMapper.selectList(new LambdaQueryWrapper<PaImportSession>()
                .eq(PaImportSession::getSessionKey, sessionKey)
                // SQL Server 的 OFFSET 必须跟在 ORDER BY 后面；session_key 唯一，按 id 排即可。
                // 少了这段 SQL Server 报「'OFFSET' 附近有语法错误」，而 H2 不报，单测抓不到。
                .orderByAsc(PaImportSession::getId)
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
        return list.isEmpty() ? null : list.get(0);
    }

    private void requireStatus(PaImportSession session, String expected) {
        if (!expected.equals(session.getStatus())) {
            throw new BusinessException("当前状态是「" + statusText(session.getStatus())
                    + "」，不能执行这个操作（需要「" + statusText(expected) + "」）");
        }
    }

    /** 更新会话：每次交互顺延过期时间（30 分钟无交互才过期） */
    private void update(PaImportSession session, java.util.function.Consumer<PaImportSession> mutator) {
        mutator.accept(session);
        session.setUpdateTime(LocalDateTime.now());
        session.setExpireTime(expireAt());
        sessionMapper.updateById(session);
    }

    private void updateStage(PaImportSession session, String stage, int progress) {
        update(session, s -> {
            s.setStage(stage);
            s.setProgress(progress);
        });
    }

    private void fail(PaImportSession session, String message, Exception e) {
        if (e != null) {
            log.error("导入会话 {} 失败：{}", session.getSessionKey(), message, e);
        } else {
            log.warn("导入会话 {} 失败：{}", session.getSessionKey(), message);
        }
        update(session, s -> {
            s.setStatus(PaImportStatus.FAILED);
            s.setError(truncate(message, 950));
            s.setStage(null);
        });
    }

    private void markExpired(PaImportSession session) {
        // 过期清理必须清空大字段：payload 里是整份 Excel 的文本，留着只是占空间
        update(session, s -> {
            s.setStatus(PaImportStatus.EXPIRED);
            s.setStage("会话已过期（超过 30 分钟无操作）");
            s.setPayload(null);
            s.setMapping(null);
            s.setOverview(null);
            s.setPending(null);
            s.setPreview(null);
            s.setSnapshot(null);
        });
        log.info("导入会话过期 sessionKey={}", session.getSessionKey());
    }

    private boolean isExpired(PaImportSession session) {
        return session.getExpireTime() != null && session.getExpireTime().isBefore(LocalDateTime.now());
    }

    private LocalDateTime expireAt() {
        return LocalDateTime.now().plusMinutes(Math.max(5, aiProps.getSessionTtlMinutes()));
    }

    // ================================================================ 序列化

    private PaExcelRaw readPayload(PaImportSession session) {
        PaExcelRaw raw = readJson(session.getPayload(), PaExcelRaw.class);
        if (raw == null) {
            throw new BusinessException("会话里没有文件内容（可能已过期），请重新上传");
        }
        return raw;
    }

    PaImportDraft readDraft(PaImportSession session) {
        PaImportDraft draft = readJson(session.getMapping(), PaImportDraft.class);
        if (draft == null) {
            throw new BusinessException("会话里还没有解析结果，请重新上传文件");
        }
        return draft;
    }

    /** 草稿存 mapping 字段（那是「模型+代码共同产出的可信结构」） */
    void saveDraft(PaImportSession session, PaImportDraft draft) {
        update(session, s -> s.setMapping(writeJson(draft)));
    }

    private PaImportPlan readPlan(PaImportSession session) {
        PaImportPlan plan = readJson(session.getUserPlan(), PaImportPlan.class);
        if (plan == null) {
            plan = new PaImportPlan();
        }
        if (plan.getSelectedSheets() == null) {
            plan.setSelectedSheets(new ArrayList<>());
        }
        if (plan.getResolved() == null) {
            plan.setResolved(new LinkedHashMap<>());
        }
        return plan;
    }

    private Map<String, String> readResolved(PaImportSession session) {
        return readPlan(session).getResolved();
    }

    private PaImportPreview readPreview(PaImportSession session) {
        return readJson(session.getPreview(), PaImportPreview.class);
    }

    private List<PaImportQuestion> readQuestions(PaImportSession session) {
        return readJsonList(session.getPending(), PaImportQuestion.class);
    }

    private List<Map<String, Object>> readDialog(PaImportSession session) {
        if (session.getDialog() == null || session.getDialog().isBlank()) {
            return new ArrayList<>();
        }
        try {
            return json.readValue(session.getDialog(), new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            log.warn("对话记录解析失败，重置：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 给抽答案用的历史：只取问答，去掉系统提示 */
    private List<Map<String, String>> readHistory(PaImportSession session) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, Object> d : readDialog(session)) {
            String kind = String.valueOf(d.get("kind"));
            if ("QUESTION".equals(kind) || "ANSWER".equals(kind) || "NOTE".equals(kind)) {
                out.add(Map.of("role", "QUESTION".equals(kind) ? "助手问" : "用户答",
                        "text", String.valueOf(d.get("text"))));
            }
        }
        return out;
    }

    private Map<String, Object> entry(String kind, String text, String slot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("text", text);
        m.put("at", LocalDateTime.now().format(TIME_FMT));
        if (slot != null) {
            m.put("slot", slot);
        }
        return m;
    }

    private <T> T readJson(String text, Class<T> type) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readValue(text, type);
        } catch (Exception e) {
            log.warn("JSON 反序列化失败（{}）：{}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private <T> List<T> readJsonList(String text, Class<T> type) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return json.readValue(text, json.getTypeFactory().constructCollectionType(List.class, type));
        } catch (Exception e) {
            log.warn("JSON 列表反序列化失败（{}）：{}", type.getSimpleName(), e.getMessage());
            return new ArrayList<>();
        }
    }

    private Object readJsonAny(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readValue(text, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            log.error("JSON 序列化失败（{}）", o.getClass().getSimpleName(), e);
            return null;
        }
    }

    // ================================================================ 小工具

    /**
     * 改计划的某个字段而不用重建整份。
     *
     * <p>会话里的 plan 是把「用户的决定」和「已确认的回答」存在一起的，
     * 任一处改都要写回整份，否则会丢另一半。</p>
     */
    private PaImportPlan planOf(PaImportSession session, java.util.function.Consumer<PaImportPlan> mutator) {
        PaImportPlan plan = readPlan(session);
        mutator.accept(plan);
        return plan;
    }

    private PaImportPlan mergePlan(PaImportPlan old, PaImportPlan incoming) {
        PaImportPlan p = old == null ? new PaImportPlan() : old;
        if (incoming == null) {
            return p;
        }
        if (incoming.getExisting() != null) {
            p.setExisting(incoming.getExisting());
        }
        p.setRegenerateFrozen(incoming.isRegenerateFrozen());
        if (incoming.getRegenerateYears() != null) {
            p.setRegenerateYears(incoming.getRegenerateYears());
        }
        p.setVoidRunning(incoming.isVoidRunning());
        p.setRelaunchRunning(incoming.isRelaunchRunning());
        if (incoming.getVoidReason() != null) {
            p.setVoidReason(incoming.getVoidReason());
        }
        if (incoming.getSelectedSheets() != null && !incoming.getSelectedSheets().isEmpty()) {
            p.setSelectedSheets(incoming.getSelectedSheets());
        }
        return p;
    }

    /** 模型不可用时的降级总览：所有 sheet 都标 IMPORT，交用户勾 */
    private PaImportOverview fallbackOverview(PaExcelRaw raw) {
        PaImportOverview overview = new PaImportOverview();
        for (PaExcelRaw.Sheet s : raw.getSheets()) {
            PaImportOverview.SheetView v = new PaImportOverview.SheetView();
            v.setSheetName(s.getName());
            v.setAction("IMPORT");
            v.setPostName(s.getName());
            v.setReason("模型不可用，未能自动判断。请自行确认这张表是不是岗位能力模型。");
            overview.getSheets().add(v);
        }
        overview.getNotes().add("模型服务当前不可用，所有工作表都按「可能是岗位模型」处理，请你手动勾选。");
        return overview;
    }

    /** 补上代码能算的规模信息（模型不参与算数，避免它数错行） */
    private void enrichOverview(PaImportOverview overview, PaExcelRaw raw) {
        Map<String, PaExcelRaw.Sheet> byName = new LinkedHashMap<>();
        for (PaExcelRaw.Sheet s : raw.getSheets()) {
            byName.put(s.getName(), s);
        }
        for (PaImportOverview.SheetView v : overview.getSheets()) {
            PaExcelRaw.Sheet sheet = byName.get(v.getSheetName());
            if (sheet == null) {
                continue;
            }
            v.setRowCount(sheet.getRows().size());
            v.setColumnCount(sheet.getLastColumn() == null ? 0
                    : PaExcelRaw.Sheet.columnIndex(sheet.getLastColumn()) + 1);
            List<String> header = new ArrayList<>();
            sheet.getRows().entrySet().stream().findFirst().ifPresent(e ->
                    e.getValue().forEach((col, val) -> header.add(col + ":" + val)));
            v.setHeaderPreview(header.size() > 8 ? header.subList(0, 8) : header);
        }
    }

    /** 只保留用户勾的 sheet（模型对整份文件识别时也用得上） */
    private PaExcelRaw subsetOf(PaExcelRaw raw, List<String> sheets) {
        Set<String> keep = new LinkedHashSet<>(sheets);
        PaExcelRaw out = new PaExcelRaw();
        out.setFileName(raw.getFileName());
        out.setFileSize(raw.getFileSize());
        for (PaExcelRaw.Sheet s : raw.getSheets()) {
            if (keep.contains(s.getName())) {
                out.getSheets().add(s);
            }
        }
        return out;
    }

    /** 模型说「还差这几项」，但代码确实还没解 → 补成追问（不阻断，只是提醒） */
    private void appendFollowUps(List<PaImportQuestion> questions, PaImportRound round,
                                 List<PaImportQuestion> asked) {
        if (round.getFollowUps() == null || round.getFollowUps().isEmpty()) {
            return;
        }
        Set<String> existingTopics = new LinkedHashSet<>();
        for (PaImportQuestion q : questions) {
            existingTopics.add(nz(q.getTopic()));
        }
        for (String followUp : round.getFollowUps()) {
            if (followUp == null || followUp.isBlank() || existingTopics.contains(followUp.trim())) {
                continue;
            }
            PaImportQuestion q = new PaImportQuestion();
            q.setType(PaImportQuestion.SUSPECT);
            q.setSlot("followup." + Math.abs(followUp.hashCode()));
            q.setTopic(followUp.trim());
            q.setQuestion(followUp.trim());
            // 追问不阻断：模型觉得需要澄清的事情，用户不答也该能导
            q.setBlocking(false);
            questions.add(q);
            existingTopics.add(followUp.trim());
        }
    }

    private static String postNames(PaImportDraft draft) {
        List<String> names = new ArrayList<>();
        for (PaImportDraft.PostDraft p : draft.getPosts()) {
            // 通用岗位（已确认不绑部门）要显式写出来，否则列表里只剩光秃秃的岗位名，
            // 用户没法从这行字判断「通用」这个确认到底生效了没有
            String prefix = p.getDepartmentId() == null && p.getDepartmentName() == null
                    ? PaImportQuestionBuilder.GENERIC_DEPARTMENT + " · "
                    : (p.getDepartmentName() == null ? "" : p.getDepartmentName() + " · ");
            names.add(prefix + p.getPostName());
        }
        return names.isEmpty() ? "（未识别出岗位）" : String.join("、", names);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** 供后台兜底任务使用：所有卡在「忙」状态的会话 */
    public List<PaImportSession> busySessions() {
        return sessionMapper.selectList(new LambdaQueryWrapper<PaImportSession>()
                .in(PaImportSession::getStatus,
                        List.of(PaImportStatus.PARSING, PaImportStatus.CHECKING, PaImportStatus.APPLYING)));
    }

    /** 把会话标失败（进程重启后，线程已经没了，状态会永远停在忙） */
    public void markFailedAfterRestart(PaImportSession session) {
        update(session, s -> {
            s.setStatus(PaImportStatus.FAILED);
            s.setStage(null);
            s.setError("服务重启导致本次导入中断，请重新上传文件（未写入任何数据）");
        });
    }

    /** 供后台清理任务使用：超时未交互的会话 */
    public int cleanExpired() {
        List<PaImportSession> list = sessionMapper.selectList(new LambdaQueryWrapper<PaImportSession>()
                .lt(PaImportSession::getExpireTime, LocalDateTime.now())
                .notIn(PaImportSession::getStatus,
                        List.of(PaImportStatus.EXPIRED, PaImportStatus.DONE, PaImportStatus.FAILED,
                                PaImportStatus.MANUAL)));
        for (PaImportSession session : list) {
            markExpired(session);
        }
        return list.size();
    }

    /** 便于其他服务读取草稿（预览/执行共用同一份） */
    public PaImportDraft draftOf(String sessionKey) {
        return readDraft(requireOwned(sessionKey));
    }

    /** 保存草稿（runDetect 装配完成后落库） */
    void saveDraftAndContinue(PaImportSession session, PaImportDraft draft,
                              List<PaImportQuestion> questions, Map<String, String> resolved) {
        saveDraft(session, draft);
        finishRound(session, draft, questions, resolved, null);
    }
}
