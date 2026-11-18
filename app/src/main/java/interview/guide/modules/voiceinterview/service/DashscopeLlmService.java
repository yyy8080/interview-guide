package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Service
@Slf4j
@RequiredArgsConstructor
public class DashscopeLlmService {

    private static final String TERMINAL_PUNCTUATION = "。！？；!?;.";

    private final LlmProviderRegistry llmProviderRegistry;
    private final VoiceInterviewPromptService promptService;
    private final ResumeRepository resumeRepository;
    private final VoiceInterviewProperties voiceInterviewProperties;
    private final PromptSanitizer promptSanitizer;

    public String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        try {
            PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);

            String provider = session.getLlmProvider();
            log.info("[VoiceInterview] Session {} using LLM provider: {}", session.getId(), provider);

            ChatClient chatClient = llmProviderRegistry.getVoiceChatClient(provider);

            ChatClient.CallResponseSpec response = chatClient.prompt()
                .system(promptContext.systemPrompt())
                .user(promptContext.userPrompt())
                .call();

            String content = response.chatResponse().getResult().getOutput().getText();
            String optimized = optimizeForVoice(content);

            log.info("LLM response generated for session {}: replyLength={}",
                     session.getId(), optimized.length());

            return optimized;

        } catch (Exception e) {
            log.error("LLM chat error for session {}: {}", session.getId(),
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            return mapLlmErrorToUserMessage(e);
        }
    }

    public String chatStream(String userInput, Consumer<String> onToken, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        return chatStreamSentences(userInput, onToken, null, session, conversationHistory);
    }

    /**
     * 流式调用 LLM，每检测到一个完整句子就回调 onSentence，同时推送实时文本给 onToken。
     * 返回完整优化后的文本。
     */
    public String chatStreamSentences(String userInput,
                                       Consumer<String> onToken,
                                       Consumer<String> onSentence,
                                       VoiceInterviewSessionEntity session,
                                       List<String> conversationHistory) {
        try {
            PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);
            String provider = session.getLlmProvider();
            log.info("[VoiceInterview] Session {} using LLM provider (sentence stream): {}", session.getId(), provider);

            ChatClient chatClient = llmProviderRegistry.getVoiceChatClient(provider);
            StringBuilder raw = new StringBuilder();
            AtomicLong lastEmitNanos = new AtomicLong(System.nanoTime());
            AtomicInteger lastEmitLength = new AtomicInteger(0);
            AtomicInteger lastSentenceEnd = new AtomicInteger(0);
            int emitIntervalMs = Math.max(80, voiceInterviewProperties.getAiStreamPushIntervalMs());
            int minCharsDelta = Math.max(4, voiceInterviewProperties.getAiStreamMinCharsDelta());

            chatClient.prompt()
                .system(promptContext.systemPrompt())
                .user(promptContext.userPrompt())
                .stream()
                .content()
                .doOnNext(token -> {
                    if (token == null || token.isEmpty()) {
                        return;
                    }
                    raw.append(token);

                    // 检测句子边界，回调 onSentence
                    if (onSentence != null && hasTerminalSince(token)) {
                        String normalized = normalizeRealtimeText(raw.toString());
                        int currentEnd = normalized.length();
                        if (currentEnd > lastSentenceEnd.get()) {
                            String sentence = normalized.substring(lastSentenceEnd.get()).trim();
                            if (!sentence.isEmpty()) {
                                onSentence.accept(sentence);
                            }
                            lastSentenceEnd.set(currentEnd);
                        }
                    }

                    // 实时文本推送
                    if (onToken == null) {
                        return;
                    }
                    long now = System.nanoTime();
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - lastEmitNanos.get());
                    int currentLength = raw.length();
                    boolean shouldEmit = elapsedMs >= emitIntervalMs && currentLength - lastEmitLength.get() >= minCharsDelta;
                    if (!shouldEmit) {
                        return;
                    }
                    String normalized = normalizeRealtimeText(raw.toString());
                    if (normalized.isBlank()) {
                        return;
                    }
                    onToken.accept(normalized);
                    lastEmitNanos.set(now);
                    lastEmitLength.set(normalized.length());
                })
                .blockLast();

            // 发送最后一段（可能不以终止标点结尾）
            if (onSentence != null) {
                String normalized = normalizeRealtimeText(raw.toString());
                if (normalized.length() > lastSentenceEnd.get()) {
                    String remaining = normalized.substring(lastSentenceEnd.get()).trim();
                    if (!remaining.isEmpty()) {
                        onSentence.accept(remaining);
                    }
                }
            }

            String optimized = optimizeForVoice(raw.toString());
            if (onToken != null && !optimized.isBlank()) {
                onToken.accept(optimized);
            }

            log.info("LLM sentence stream response for session {}: replyLength={}",
                session.getId(), optimized.length());
            return optimized;
        } catch (Exception e) {
            log.error("LLM sentence stream error for session {}: {}", session.getId(),
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            return mapLlmErrorToUserMessage(e);
        }
    }

    private PromptContext buildPromptContext(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        String resumeText = null;
        if (session.getResumeId() != null) {
            ResumeEntity resume = resumeRepository.findById(session.getResumeId()).orElse(null);
            if (resume != null) {
                resumeText = resume.getResumeText();
            }
        }

        String systemPrompt = promptService.generateSystemPromptWithContext(
            session.getSkillId(), resumeText, session.getCustomJdText(), session.getDifficulty());

        StringBuilder promptBuilder = new StringBuilder();
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            promptBuilder.append("【之前的对话】\n");
            for (String message : conversationHistory) {
                promptBuilder.append(promptSanitizer.sanitize(message)).append("\n");
            }
            promptBuilder.append("\n【当前对话】\n");
        }
        promptBuilder.append("用户：").append(
            promptSanitizer.wrapWithDelimiters("input", promptSanitizer.sanitize(userInput)));
        return new PromptContext(systemPrompt, promptBuilder.toString());
    }

    private String mapLlmErrorToUserMessage(Exception e) {
        String errorMessage = e.getMessage();
        if (errorMessage != null) {
            if (errorMessage.contains("403") || errorMessage.contains("ACCESS_DENIED") ||
                errorMessage.contains("Authentication")) {
                return "AI 服务认证失败，请检查 API Key 配置";
            } else if (errorMessage.contains("timeout") || errorMessage.contains("Timeout")) {
                return "AI 服务响应超时，请稍后重试";
            } else if (errorMessage.contains("429") || errorMessage.contains("rate limit") ||
                       errorMessage.contains("quota")) {
                return "AI 服务调用频率超限，请稍后重试";
            } else if (errorMessage.contains("connection") || errorMessage.contains("network")) {
                return "AI 服务网络连接失败，请检查网络";
            }
        }
        return "抱歉，AI 服务暂时不可用，请稍后重试";
    }

    private String optimizeForVoice(String content) {
        String normalized = normalizeRealtimeText(content);
        if (normalized.isBlank()) {
            return "请继续。";
        }

        int maxChars = Math.max(80, voiceInterviewProperties.getAiQuestionMaxChars());
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        String truncated = normalized.substring(0, maxChars);
        int lastTerminal = -1;
        for (int i = truncated.length() - 1; i >= 0; i--) {
            if (TERMINAL_PUNCTUATION.indexOf(truncated.charAt(i)) >= 0) {
                lastTerminal = i;
                break;
            }
        }
        if (lastTerminal >= maxChars / 2) {
            return truncated.substring(0, lastTerminal + 1);
        }

        return truncated + "…";
    }

    private String normalizeRealtimeText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content
            .replace("**", "")
            .replace("```", "")
            .replace("`", "")
            .replaceAll("(?m)^\\s*[-*+]\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private boolean hasTerminalSince(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (TERMINAL_PUNCTUATION.indexOf(token.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 为绑定了候选人简历的语音面试生成首轮开场提问：直接点名候选人简历中的具体项目或经历进行真实性核验。
     * 若会话未关联简历、简历文本为空或 LLM 调用失败，返回 null 由调用方回退默认开场白。
     */
    public String generateResumeOpeningQuestion(VoiceInterviewSessionEntity session) {
        if (session == null || session.getResumeId() == null) {
            return null;
        }
        try {
            ResumeEntity resume = resumeRepository.findById(session.getResumeId()).orElse(null);
            if (resume == null || resume.getResumeText() == null || resume.getResumeText().isBlank()) {
                return null;
            }
            String systemPrompt = promptService.generateSystemPromptWithContext(
                session.getSkillId(), resume.getResumeText(), session.getCustomJdText(), session.getDifficulty());
            String openingInstruction = "现在开始本场语音面试开场。请用简短一句话说明我是本场面试官且已仔细看过你的简历，"
                + "然后直接点出候选人简历中写到的某一个具体项目名称、核心模块或量化成果，"
                + "提出第一个用于核验简历真实性的问题（请候选人说明本人独立负责的部分与具体技术实现细节）。"
                + "总长度控制在 2-3 句话，纯口语文本，不要使用列表或 Markdown。";

            ChatClient chatClient = llmProviderRegistry.getVoiceChatClient(session.getLlmProvider());
            ChatClient.CallResponseSpec response = chatClient.prompt()
                .system(systemPrompt)
                .user(openingInstruction)
                .call();
            String content = response.chatResponse().getResult().getOutput().getText();
            String optimized = optimizeForVoice(content);
            return optimized == null || optimized.isBlank() ? null : optimized;
        } catch (Exception e) {
            log.warn("生成简历定制语音开场题失败，回退默认简历核验开场白: sessionId={}, error={}",
                session.getId(), ErrorLogSanitizer.summarize(e));
            return null;
        }
    }

    private record PromptContext(String systemPrompt, String userPrompt) {}
}
