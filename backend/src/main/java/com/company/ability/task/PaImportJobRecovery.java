package com.company.ability.task;

import com.company.ability.entity.PaImportSession;
import com.company.ability.service.PaImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 导入会话的两件后台维护：重启兜底 + 过期清理。
 *
 * <p><b>重启兜底为什么必须有</b>：会话状态落库，异步线程在内存里。进程一重启，
 * 上次卡在 CHECKING / APPLYING 的会话就永远停在「忙」—— 前端无限转圈，
 * 而用户既不能重传（会被「上一次还在处理中」拦住）也不能回答。
 * 这里在启动时把所有「忙」状态的任务一次性标记失败，让用户能重新开始。</p>
 *
 * <p>标记为 FAILED 而不是 PARSING 重跑：重跑需要原始文件（payload 还在），
 * 但模型调用到一半的中间态（已识别几张表、用户已答到第几轮）无法精确恢复，
 * 重跑等于把用户已经确认过的内容悄悄丢掉。让他重来一遍更诚实。</p>
 *
 * <p>过期清理：30 分钟无交互 → EXPIRED，并清空 payload 等大字段（那是整份 Excel 的文本）。
 * 保留瘦记录便于事后查「上次导入到底卡在哪」。</p>
 */
@Slf4j
@Component
public class PaImportJobRecovery {

    private final PaImportService importService;

    public PaImportJobRecovery(PaImportService importService) {
        this.importService = importService;
    }

    /** 启动兜底：把上次进程留下的「忙」状态一次性清掉 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            List<PaImportSession> busy = importService.busySessions();
            for (PaImportSession session : busy) {
                importService.markFailedAfterRestart(session);
            }
            if (!busy.isEmpty()) {
                log.warn("启动兜底：{} 个导入会话因服务重启被标记失败", busy.size());
            }
        } catch (Exception e) {
            // 兜底任务失败不能影响启动：它只是清理，不影响新会话
            log.error("导入会话启动兜底失败", e);
        }
    }

    /** 每 5 分钟清一次过期会话（首次延迟 2 分钟，避开启动时的其它初始化） */
    @Scheduled(initialDelay = 120_000L, fixedDelay = 300_000L)
    public void cleanExpired() {
        try {
            int cleaned = importService.cleanExpired();
            if (cleaned > 0) {
                log.info("导入会话过期清理：{} 个", cleaned);
            }
        } catch (Exception e) {
            log.error("导入会话过期清理失败", e);
        }
    }
}
