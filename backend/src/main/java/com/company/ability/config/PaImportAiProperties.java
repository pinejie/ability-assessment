package com.company.ability.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 能力结构导入的模型配置（前缀 {@code pa.import.ai}）。
 *
 * <p><b>凭证走环境变量 / config/secret.yml，仓库里零明文</b>（与 hr-report 的 AI 配置同口径）。
 * 变量名沿用网关的通用命名，这样同一台机器上两个项目共用一份凭证文件：
 * {@code ANTHROPIC_BASE_URL} / {@code ANTHROPIC_AUTH_TOKEN} / {@code ANTHROPIC_MODEL}。</p>
 *
 * <p><b>max-tokens 默认 16384 不是随手写的</b>：实测（2026-11-21）这个模型是推理模型，
 * 思考过程也计入输出额度 —— 给 2000 时输出直接是空字符串（思考吃满就截断了），
 * 给 8192 才能稳定吐出一份列映射 JSON；解析整张表的结构要更大。</p>
 *
 * <p>缺配置时服务仍能启动，只在真正要调模型时报错，并由前端降级到手工映射。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "pa.import.ai")
public class PaImportAiProperties {

    /** 网关基地址，如 http://127.0.0.1:3457 */
    private String baseUrl;

    /** 网关凭证 */
    private String apiKey;

    /** 模型名，如 deepseek/deepseek-v4.1-flash */
    private String model;

    /** 单次调用读超时（秒）。实测单次 18~25 秒，留足余量 */
    private int timeoutSeconds = 120;

    /** 输出额度：推理过程的思考也占用，给小了会输出空串（实测） */
    private int maxTokens = 16384;

    /** 连接超时（秒） */
    private int connectTimeoutSeconds = 10;

    /** 追问轮次安全阀：超过就转手工映射，别让用户无限转圈 */
    private int maxRounds = 10;

    /** 会话无交互过期（分钟）：每次交互刷新 */
    private int sessionTtlMinutes = 30;

    /** 是否启用模型。关掉后整条链路降级为手工映射（排查网关问题时用） */
    private boolean enabled = true;

    public boolean configured() {
        return enabled && baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && model != null && !model.isBlank();
    }
}
