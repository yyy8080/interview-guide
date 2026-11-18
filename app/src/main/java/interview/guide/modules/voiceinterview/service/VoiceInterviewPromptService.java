package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class VoiceInterviewPromptService {

    private final PromptSanitizer promptSanitizer;

    public VoiceInterviewPromptService(PromptSanitizer promptSanitizer) {
        this.promptSanitizer = promptSanitizer;
    }

    private static final String VOICE_RESPONSE_CONSTRAINTS = """
            【语音面试输出约束】
            1. 每轮只问 1 个主问题，必要时最多补 1 个短追问。
            2. 总长度控制在 2-4 句，避免长段落、列表、Markdown、代码块。
            3. 不要重复开场白，不要复述上一轮已问过的完整问题。
            4. 若候选人回答过短或含糊，直接追问一个具体的技术细节或给出提示引导，不要简单确认后停止。
            5. 当候选人明确要求换题时，立即切换到新的技术方向，不要停留在当前话题。
            6. 语气简洁直接，适配口语对话。
            """;

    private static final String SKILL_TOOL_INSTRUCTION = """
            你是一位 %s 方向的面试官。
            如果尚未加载完整的角色设定，请调用 Skill 工具（command: %s）加载该技能的 SKILL.md。
            工具输出包含完整的面试官角色和出题规则，后续对话应基于该角色进行。
            """;

    private static final String CUSTOM_JD_INSTRUCTION = """
            你是一位企业招聘面试官，正在为下述岗位进行线上语音面试。
            请围绕岗位 JD 的核心要求与候选人简历出题，考察技术深度、项目经验与岗位匹配度。
            """;

    public String generateSystemPromptWithContext(String skillId, String resumeText) {
        return generateSystemPromptWithContext(skillId, resumeText, null);
    }

    /**
     * 生成系统提示词；customJdText 非空时附加岗位 JD（招聘场景），
     * skillId 为 custom 时不加载 Skill 工具，改用 JD 驱动的面试官设定。
     */
    public String generateSystemPromptWithContext(String skillId, String resumeText, String customJdText) {
        StringBuilder prompt = new StringBuilder();
        boolean hasJd = customJdText != null && !customJdText.isBlank();
        boolean customSkill = "custom".equals(skillId);

        if (customSkill && hasJd) {
            prompt.append(CUSTOM_JD_INSTRUCTION);
        } else if (skillId != null && !skillId.isBlank() && !customSkill) {
            prompt.append(String.format(SKILL_TOOL_INSTRUCTION, skillId, skillId));
        }

        if (hasJd) {
            prompt.append("\n\n【招聘岗位 JD】\n")
                .append(promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(customJdText)));
        }

        prompt.append("\n\n").append(VOICE_RESPONSE_CONSTRAINTS);

        if (resumeText != null && !resumeText.isEmpty()) {
            String safeResume = promptSanitizer.sanitize(resumeText);
            prompt.append("\n\n【实时语音面试 - 候选人简历内容】\n")
                .append("你已查阅过候选人简历。首轮仅用一句话说明已查阅，并立即进入首个问题。\n\n")
                .append("【简历解析文本】\n")
                .append(promptSanitizer.wrapWithDelimiters("resume", safeResume));
        }

        prompt.append(PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION);
        return prompt.toString();
    }
}
