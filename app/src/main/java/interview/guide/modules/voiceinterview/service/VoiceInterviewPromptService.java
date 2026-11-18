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
        return generateSystemPromptWithContext(skillId, resumeText, null, null);
    }

    public String generateSystemPromptWithContext(String skillId, String resumeText, String customJdText) {
        return generateSystemPromptWithContext(skillId, resumeText, customJdText, null);
    }

    /**
     * 生成系统提示词；customJdText 非空时附加岗位 JD（招聘场景），
     * difficulty 非空时注入面试者定位（初级 / 中级 / 高级）对应的出题深度要求。
     */
    public String generateSystemPromptWithContext(String skillId, String resumeText, String customJdText,
                                                  String difficulty) {
        StringBuilder prompt = new StringBuilder();
        boolean hasJd = customJdText != null && !customJdText.isBlank();
        boolean customSkill = "custom".equals(skillId);

        if (customSkill && hasJd) {
            prompt.append(CUSTOM_JD_INSTRUCTION);
        } else if (skillId != null && !skillId.isBlank() && !customSkill) {
            prompt.append(String.format(SKILL_TOOL_INSTRUCTION, skillId, skillId));
        }

        if (difficulty != null && !difficulty.isBlank()) {
            prompt.append("\n\n【面试者定位与出题深度要求】\n")
                .append(describeVoiceDifficulty(difficulty));
        }

        if (hasJd) {
            prompt.append("\n\n【招聘岗位 JD】\n")
                .append(promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(customJdText)));
        }

        prompt.append("\n\n").append(VOICE_RESPONSE_CONSTRAINTS);

        if (resumeText != null && !resumeText.isBlank()) {
            String safeResume = promptSanitizer.sanitize(resumeText);
            prompt.append("\n\n【实时语音面试 - 候选人简历内容与真实性核验（重点任务）】\n")
                .append("1. 你已查阅过候选人简历，本场语音面试必须重点核验候选人简历内容的真实性，防止简历夸大或虚构经历。\n")
                .append("2. 至少一半以上的主问题与追问必须直接锚定下述简历中明确写出的具体项目名称、所负责模块、技术选型或量化指标，点名简历原文细节让候选人说明本人具体完成了什么。\n")
                .append("3. 追问聚焦可验证的实现细节：询问当时的业务背景与约束、为什么这样选型、关键代码/表结构/接口如何落地、量化指标如何测算、踩过什么真实问题。\n")
                .append("4. 若候选人对自己简历中声称负责的内容回答空泛套话、前后矛盾或答不出基本实现细节，必须围绕该细节追问核实一次，不要轻易滑过。\n\n")
                .append("【简历解析文本】\n")
                .append(promptSanitizer.wrapWithDelimiters("resume", safeResume));
        }

        prompt.append(PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION);
        return prompt.toString();
    }

    private static String describeVoiceDifficulty(String difficulty) {
        return switch (difficulty.trim().toLowerCase()) {
            case "junior" -> "当前面试者自选定位为【初级】（0-1年经验）。请围绕核心基础概念、常用技术栈基本用法与简历中的具体实现提问，追问保持温和引导，避免超大规模分布式架构或偏门源码深挖。";
            case "senior" -> "当前面试者自选定位为【高级】（3年以上经验）。请围绕底层核心原理、系统架构设计权衡、高并发高可用方案、性能瓶颈调优与复杂故障排查提问，追问直击边界场景与工程取舍，减少纯概念背诵题。";
            default -> "当前面试者自选定位为【中级】（1-3年经验）。请围绕组件底层工作原理、并发/事务/缓存/索引机制、工程实践与常见问题排障提问，考察知其然更知其所以然。";
        };
    }
}
