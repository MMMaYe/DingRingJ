package com.dingring.app.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dingring.app.dto.request.SaveSkillRequest;
import com.dingring.app.dto.response.SkillDTO;
import com.dingring.common.exception.ParamException;
import com.dingring.domain.skill.Skill;
import com.dingring.domain.skill.SkillRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillAppService} 单元测试。
 * <p>重点覆盖 Phase G 新增的 SCENE 作用域规则（设计 §7）：sceneKey 必填/白名单校验、
 * toolNames/agentId 禁填、scope 切换时 sceneKey 联动清空、systemPrompt 超长软限制 WARN、
 * 按场景过滤查询（管理面需含 INACTIVE）。
 */
@DisplayName("SkillAppService SKILL 管理服务")
class SkillAppServiceTest {

    private static final String EVENT_CODE_PROMPT_TOO_LONG = "SKILL_SCENE_PROMPT_TOO_LONG";

    private SkillRepository skillRepository;
    private SkillAppService service;

    // LogHelper 走 SLF4J → Logback，挂内存 appender 才能断言软限制 WARN
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        skillRepository = mock(SkillRepository.class);
        service = new SkillAppService(skillRepository);

        logbackLogger = (Logger) LoggerFactory.getLogger(SkillAppService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logbackLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(logAppender);
    }

    /** 合法的 SCENE 技能请求基线（card 场景，无工具无 Agent） */
    private SaveSkillRequest sceneRequest() {
        SaveSkillRequest req = new SaveSkillRequest();
        req.setName("card-review-ready");
        req.setDescription("卡片复习就绪约束");
        req.setSystemPrompt("本节约束卡片的检索与复习友好性");
        req.setScope(Skill.SCOPE_SCENE);
        req.setSceneKey("card");
        return req;
    }

    @Nested
    @DisplayName("create/update SCENE 作用域校验")
    class SceneValidation {

        @Test
        @DisplayName("scope=SCENE 缺 sceneKey（null/空白）→ ParamException")
        void shouldThrowWhenSceneKeyMissing() {
            SaveSkillRequest req = sceneRequest();
            req.setSceneKey(null);
            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("scope=SCENE 时必须指定 sceneKey");

            req.setSceneKey("  ");
            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("scope=SCENE 时必须指定 sceneKey");

            verify(skillRepository, never()).save(any());
        }

        @Test
        @DisplayName("scope=SCENE 传非法 sceneKey（work）→ ParamException")
        void shouldThrowWhenSceneKeyInvalid() {
            SaveSkillRequest req = sceneRequest();
            req.setSceneKey("work");

            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("非法的沉淀场景键")
                    .hasMessageContaining("conclude/card/topic-profile");

            verify(skillRepository, never()).save(any());
        }

        @Test
        @DisplayName("scope=SCENE 带 toolNames → ParamException（沉淀链路是无工具生成）")
        void shouldThrowWhenToolNamesPresent() {
            SaveSkillRequest req = sceneRequest();
            req.setToolNames("knowledge_search");

            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("toolNames 必须为空");

            verify(skillRepository, never()).save(any());
        }

        @Test
        @DisplayName("scope=SCENE 带 agentId → ParamException（SCENE 不参与 Agent 装配）")
        void shouldThrowWhenAgentIdPresent() {
            SaveSkillRequest req = sceneRequest();
            req.setAgentId(1L);

            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("agentId 必须为空");

            verify(skillRepository, never()).save(any());
        }

        @Test
        @DisplayName("update 路径同样走 SCENE 校验：缺 sceneKey → ParamException")
        void shouldThrowOnUpdateWhenSceneKeyMissing() {
            Skill existing = new Skill();
            existing.setId(1L);
            existing.setName("card-review-ready");
            existing.setScope(Skill.SCOPE_GLOBAL);
            when(skillRepository.findById(1L)).thenReturn(Optional.of(existing));

            SaveSkillRequest req = sceneRequest();
            req.setSceneKey(null);

            assertThatThrownBy(() -> service.update(1L, req))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("scope=SCENE 时必须指定 sceneKey");

            verify(skillRepository, never()).update(any());
        }

        @Test
        @DisplayName("合法 SCENE 请求正常落库并回显 sceneKey")
        void shouldCreateSceneSkill() {
            when(skillRepository.findByName(anyString())).thenReturn(Optional.empty());
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> inv.getArgument(0));

            SkillDTO dto = service.create(sceneRequest());

            ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
            verify(skillRepository).save(captor.capture());
            assertThat(captor.getValue().getScope()).isEqualTo(Skill.SCOPE_SCENE);
            assertThat(captor.getValue().getSceneKey()).isEqualTo("card");
            assertThat(captor.getValue().getAgentId()).isNull();
            assertThat(dto.getSceneKey()).isEqualTo("card");
        }
    }

    @Nested
    @DisplayName("scope 切换时 sceneKey 联动清空")
    class SceneKeyResetOnScopeSwitch {

        @Test
        @DisplayName("create scope=GLOBAL 但请求带 sceneKey → 落库 sceneKey=null")
        void shouldResetSceneKeyWhenScopeGlobalOnCreate() {
            when(skillRepository.findByName(anyString())).thenReturn(Optional.empty());

            SaveSkillRequest req = sceneRequest();
            req.setScope(Skill.SCOPE_GLOBAL);
            req.setSceneKey("card");

            service.create(req);

            ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
            verify(skillRepository).save(captor.capture());
            // 与 agentId 的联动处理一致：非 SCENE 作用域下 sceneKey 无语义，落库前清空
            assertThat(captor.getValue().getSceneKey()).isNull();
        }

        @Test
        @DisplayName("update 从 SCENE 切回 GLOBAL → sceneKey 被清空")
        void shouldResetSceneKeyWhenScopeSwitchBackToGlobal() {
            Skill existing = new Skill();
            existing.setId(1L);
            existing.setName("card-review-ready");
            existing.setScope(Skill.SCOPE_SCENE);
            existing.setSceneKey("card");
            when(skillRepository.findById(1L)).thenReturn(Optional.of(existing));

            SaveSkillRequest req = sceneRequest();
            req.setScope(Skill.SCOPE_GLOBAL);
            req.setSceneKey(null);

            service.update(1L, req);

            verify(skillRepository).update(existing);
            assertThat(existing.getScope()).isEqualTo(Skill.SCOPE_GLOBAL);
            assertThat(existing.getSceneKey()).isNull();
            assertThat(existing.getAgentId()).isNull();
        }
    }

    @Nested
    @DisplayName("R2 软限制：SCENE 技能 systemPrompt 超 800 字 WARN")
    class ScenePromptSoftLimit {

        @Test
        @DisplayName("create SCENE 正文 801 字 → 保存成功且打 WARN（不阻断）")
        void shouldWarnWhenScenePromptTooLongOnCreate() {
            when(skillRepository.findByName(anyString())).thenReturn(Optional.empty());

            SaveSkillRequest req = sceneRequest();
            req.setSystemPrompt("长".repeat(801));

            SkillDTO dto = service.create(req);

            verify(skillRepository).save(any(Skill.class));
            assertThat(dto.getSceneKey()).isEqualTo("card");
            assertThat(logAppender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains(EVENT_CODE_PROMPT_TOO_LONG);
                    });
        }

        @Test
        @DisplayName("update SCENE 正文超 800 字 → 同样打 WARN")
        void shouldWarnWhenScenePromptTooLongOnUpdate() {
            Skill existing = new Skill();
            existing.setId(1L);
            existing.setName("card-review-ready");
            existing.setScope(Skill.SCOPE_SCENE);
            existing.setSceneKey("card");
            when(skillRepository.findById(1L)).thenReturn(Optional.of(existing));

            SaveSkillRequest req = sceneRequest();
            req.setSystemPrompt("长".repeat(900));

            service.update(1L, req);

            verify(skillRepository).update(existing);
            assertThat(logAppender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains(EVENT_CODE_PROMPT_TOO_LONG);
                    });
        }

        @Test
        @DisplayName("正文恰好 800 字（边界内）→ 不打 WARN")
        void shouldNotWarnWhenScenePromptAtLimit() {
            when(skillRepository.findByName(anyString())).thenReturn(Optional.empty());

            SaveSkillRequest req = sceneRequest();
            req.setSystemPrompt("短".repeat(800));

            service.create(req);

            verify(skillRepository).save(any(Skill.class));
            assertThat(logAppender.list)
                    .noneMatch(event -> event.getFormattedMessage().contains(EVENT_CODE_PROMPT_TOO_LONG));
        }
    }

    @Nested
    @DisplayName("listBySceneKey 按沉淀场景过滤（含 INACTIVE）")
    class ListBySceneKey {

        @Test
        @DisplayName("只返回匹配 sceneKey 的 SCENE 技能，含 INACTIVE，排除其他作用域/场景")
        void shouldReturnOnlyMatchingSceneSkillsIncludingInactive() {
            Skill cardActive = sceneSkill(1L, "card-review-ready", "card", Skill.STATUS_ACTIVE);
            Skill cardInactive = sceneSkill(2L, "card-format", "card", Skill.STATUS_INACTIVE);
            Skill concludeSkill = sceneSkill(3L, "conclude-article", "conclude", Skill.STATUS_ACTIVE);
            Skill globalSkill = new Skill();
            globalSkill.setId(4L);
            globalSkill.setName("rag-search");
            globalSkill.setScope(Skill.SCOPE_GLOBAL);
            when(skillRepository.findAll()).thenReturn(List.of(cardActive, cardInactive, concludeSkill, globalSkill));

            List<SkillDTO> result = service.listBySceneKey("card");

            assertThat(result).extracting(SkillDTO::getName)
                    .containsExactlyInAnyOrder("card-review-ready", "card-format");
            assertThat(result).allMatch(dto -> "card".equals(dto.getSceneKey()));
        }

        @Test
        @DisplayName("非法 sceneKey → ParamException（与 create/update 校验口径一致）")
        void shouldThrowWhenSceneKeyInvalid() {
            assertThatThrownBy(() -> service.listBySceneKey("work"))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("非法的沉淀场景键");
        }
    }

    private Skill sceneSkill(Long id, String name, String sceneKey, String status) {
        Skill skill = new Skill();
        skill.setId(id);
        skill.setName(name);
        skill.setScope(Skill.SCOPE_SCENE);
        skill.setSceneKey(sceneKey);
        skill.setStatus(status);
        return skill;
    }
}
