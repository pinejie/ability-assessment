package com.company.ability.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 集成配置的密钥加解密（AES-256-GCM）。
 *
 * <p><b>为什么需要它</b>：维度集成配置存进数据库后，client_secret / push_secret
 * 变成表里的一列。数据库备份、只读账号、运维日志都可能把它带出去 ——
 * 而 secret.yml 之所以被 gitignore，就是为了不进版本库。配置进库不能把这条底线丢了。</p>
 *
 * <p><b>主密钥</b>：来自环境变量 {@code INTEGRATION_MASTER_KEY}（32 字节）。
 * 不进库、不进 Git、不进日志。<b>丢了所有维度配置要重录</b> —— 没有找回途径，
 * 这是刻意的：能找回的主密钥等于没有加密。</p>
 *
 * <p><b>密文格式</b>：{@code enc:v1:} + Base64(IV(12) + 密文 + Tag(16))。
 * 前缀的作用是让「明文」与「密文」在同一个字段里可以共存：
 * 上线初期可能有人手工往库里贴明文调试，读取时能识别出来并给出明确提示，
 * 而不是拿明文去解密然后报一个看不懂的 padding 错误。</p>
 *
 * <p><b>为什么用 GCM 而不是 CBC</b>：GCM 自带完整性校验（Tag）。
 * CBC 改了密文解出来是乱码但仍「成功」，配置被篡改后表现为「密钥错误」，
 * 排查起来会绕远路。</p>
 *
 * <p><b>未配置主密钥时</b>：不阻断启动。因为测试环境、纯业务开发都不需要它。
 * 真要用到加解密时会抛出明确异常，而不是返回一个空密钥把数据加密成垃圾。</p>
 */
@Slf4j
@Component
public class SecretCipher {

    /** 密文前缀，带版本号便于将来换算法 */
    static final String PREFIX = "enc:v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;      // AES-256
    private static final int IV_BYTES = 12;       // GCM 推荐 96 bit
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher(@Value("${integration.master-key:}") String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            this.key = null;
            log.warn("INTEGRATION_MASTER_KEY 未配置：维度集成配置的密钥无法加解密。"
                    + "生产环境必须配置，否则登录/推送读不到 client_secret。");
            return;
        }
        byte[] raw = decodeMasterKey(masterKey);
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("INTEGRATION_MASTER_KEY 必须是 32 字节（AES-256），"
                    + "当前 " + raw.length + " 字节。生成方式：openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** 主密钥是否已配置（未配置时加解密都会失败，调用方可用它给出更友好的提示） */
    public boolean isConfigured() {
        return key != null;
    }

    /**
     * 加密。已经是密文时原样返回 —— 允许对同一字段重复调用
     * （例如「保存」时前端回传的就是密文，不该再套一层）。
     */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        if (isEncrypted(plain)) {
            return plain;
        }
        requireKey("加密");
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解密。明文（无前缀）原样返回，便于兼容手工填库的调试数据。
     *
     * @throws IllegalStateException 主密钥未配置、密文损坏、或被篡改
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!isEncrypted(stored)) {
            return stored;
        }
        requireKey("解密");
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度不足");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 最常见的原因是主密钥换了。这个提示比 "AEADBadTagException" 有用得多
            throw new IllegalStateException(
                    "解密失败，通常是 INTEGRATION_MASTER_KEY 与加密时不一致（换过主密钥需要重录密钥配置）："
                            + e.getMessage(), e);
        }
    }

    /** 是否为本类产生的密文 */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 展示用掩码：不暴露任何密文或明文片段。
     * 页面上永远显示这个，保存时若值未变就不回写。
     */
    public static String mask() {
        return "******";
    }

    private void requireKey(String action) {
        if (key == null) {
            throw new IllegalStateException(
                    action + "失败：INTEGRATION_MASTER_KEY 未配置。"
                            + "生成方式：openssl rand -base64 32，配到该环境变量后重启。");
        }
    }

    /**
     * 主密钥支持两种写法：Base64（openssl rand -base64 32 的输出）或 32 字符原文。
     * 后者是为了手工配置方便，避免运维为了凑 Base64 而反复试错。
     */
    private static byte[] decodeMasterKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value.trim());
            if (decoded.length == KEY_BYTES) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // 不是合法 Base64，按原文处理
        }
        return value.trim().getBytes(StandardCharsets.UTF_8);
    }
}
