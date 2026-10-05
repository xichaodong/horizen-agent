package dev.horizen.agent.web.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 仅保留旧启用标志，供宿主拒绝不支持的独立模式。 */
@ConfigurationProperties(prefix = "horizen.agent.skill-release")
@Data
public class SkillReleaseProperties {
    /** 是否启用Skill发布对应的功能。 */
    private boolean enabled;
}
