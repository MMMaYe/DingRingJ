package com.dingring.domain.skill;

import java.util.Arrays;
import java.util.Optional;

/**
 * 沉淀场景白名单（Phase G）。
 * <p>为什么用枚举而非字符串常量：场景键本质是封闭集合，枚举让端口边界
 * （{@code SkillLoaderService} 签名、三个注入点、AppService 校验）获得编译期类型安全——
 * 注入点拼错场景键直接编译失败，而非运行期校验才发现；校验入口也从手维护
 * ALL 集合收敛为 {@link #fromKey(String)} 单点。
 * <p>DB scene_key 列存储的仍是字符串（与 scope/status 的既有字符串约定一致），
 * 枚举只出现在端口与调用方边界，避免 MyBatis/JSON 适配成本。
 */
public enum SkillScene {
    /** 结论生成（知识蒸馏）场景 */
    CONCLUDE("conclude"),
    /** 知识卡片萃取场景 */
    CARD("card"),
    /** 用户话题画像场景 */
    TOPIC_PROFILE("topic-profile");

    /** DB scene_key 列存储值 */
    private final String key;

    SkillScene(String key) { this.key = key; }

    public String key() { return key; }

    /** DB/DTO 字符串 → 枚举（管理面唯一校验入口：无法解析即非法场景键） */
    public static Optional<SkillScene> fromKey(String key) {
        return Arrays.stream(values()).filter(s -> s.key.equals(key)).findFirst();
    }
}
