package com.company.ability.config;

import com.company.ability.context.DimensionContext;
import com.company.ability.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 导入用的异步线程池 + 上下文传递。
 *
 * <p><b>为什么必须手工传上下文</b>：{@code DimensionContext} 是原始 ThreadLocal、
 * 登录身份在 {@code SecurityContextHolder}（也是 ThreadLocal）。
 * 丢进线程池以后两个都读不到 —— 后果不是报错而是<b>静默写错租户</b>：
 * 租户拦截器在「维度为空时跳过过滤」，于是插入的数据会落到默认维度，
 * 而更新语句会跨维度命中。这类错不会抛异常，只会让另一个租户的数据被改。
 * 所以这里的 {@link #wrap} 是强制的，不给「忘了传」的机会。</p>
 *
 * <p>池子刻意小（2 个线程）：导入是重活（一次模型调用 18~25 秒），
 * 堆线程不会更快，只会同时打满网关的并发限制。多出来的请求排队等即可 —— 反正前端在轮询。</p>
 *
 * <p>队列满时的拒绝策略是 {@code CallerRunsPolicy}：让调用线程（HTTP 线程）自己跑，
 * 表现是那个请求变慢，而不是任务被静默丢弃（丢弃会让会话永远停在 CHECKING，
 * 用户看到无限转圈，比慢几分钟难受得多）。</p>
 */
@Slf4j
@Configuration
public class PaImportAsyncConfig {

    public static final String EXECUTOR = "paImportExecutor";

    @Bean(EXECUTOR)
    public Executor paImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("pa-import-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：正在跑的导入必须跑完，否则会话停在 APPLYING，
        // 用户重进页面看到「正在写入」但库其实一半都没写（事务已回滚，数据是安全的，只是状态骗人）
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 把当前请求的维度与登录身份带到异步线程里。
     *
     * <p>用法（异步任务的开头必须包一层）：</p>
     * <pre>
     * executor.execute(PaImportAsyncConfig.wrap(() -> { ... }));
     * </pre>
     *
     * <p>catch 住所有异常只兜「线程不能死」这一层：真正的业务失败由被包裹的 lambda
     * 自己写进会话状态（status=FAILED + error），这里再抛也没人接。</p>
     */
    public static Runnable wrap(Runnable task) {
        Long dimensionId = DimensionContext.get();
        LoginUser user = null;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser lu) {
            user = lu;
        }
        final LoginUser captured = user;

        return () -> {
            try {
                if (dimensionId != null) {
                    DimensionContext.set(dimensionId);
                }
                if (captured != null) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(captured, null, List.of()));
                }
                task.run();
            } catch (Exception e) {
                log.error("导入异步任务未捕获异常", e);
            } finally {
                DimensionContext.clear();
                SecurityContextHolder.clearContext();
            }
        };
    }
}
