package interview.guide.modules.recruitment.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.recruitment.model.BatchResumeImageExtractDTO;
import interview.guide.modules.recruitment.model.ResumeImageExtractItemDTO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 批量简历图片联系信息 AI 识别服务：
 * <ul>
 *   <li>支持 HR 后台批量上传多张简历截图 / 拍照图片（PNG / JPG / WEBP / GIF / BMP）</li>
 *   <li>自动调用多模态大模型识别每张简历图片中的候选人邮箱地址、姓名与所在地/联系地址</li>
 *   <li>单张图片格式不符、缺少邮箱或调用失败时独立提示，不阻断同批其余图片识别</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeImageExtractService {

  private static final int MAX_CONCURRENT_VISION_CALLS = 4;
  private static final int COMPRESS_THRESHOLD_BYTES = 3 * 1024 * 1024;
  private static final int MAX_IMAGE_DIMENSION = 2200;
  private static final String DASHSCOPE_PROVIDER_ID = "dashscope";
  private static final String FALLBACK_VISION_MODEL = "qwen-vl-plus";

  private static final Pattern EMAIL_FINDER_PATTERN =
      Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
  private static final Pattern CODE_FENCE_PATTERN =
      Pattern.compile("^```(?:json)?\\s*|\\s*```$", Pattern.CASE_INSENSITIVE);
  private static final Pattern NAME_PREFIX_PATTERN =
      Pattern.compile("^(?:候选人姓名|求职者姓名|姓名|求职者|候选人|Name)\\s*[:：]\\s*", Pattern.CASE_INSENSITIVE);
  private static final Pattern EMAIL_PREFIX_PATTERN =
      Pattern.compile("^(?:电子邮箱|联系邮箱|邮箱地址|邮箱|E-?mail)\\s*[:：]\\s*", Pattern.CASE_INSENSITIVE);
  private static final Pattern LOCATION_PREFIX_PATTERN =
      Pattern.compile("^(?:现居城市|所在城市|现居地|居住地|所在地|联系地址|地址|Location|Address)\\s*[:：]\\s*",
          Pattern.CASE_INSENSITIVE);

  private static final String SYSTEM_PROMPT = """
      你是一名专业的企业招聘简历图像信息提取助手。
      请仔细阅读上传的候选人简历图片（可能是完整简历长图、手机拍照或招聘平台截图），准确提取图片中的候选人姓名、电子邮箱地址、现居城市/联系地址以及手机号。
      严格遵守以下规则：
      1) 严禁编造或猜测图片中不存在的邮箱、姓名或地址；若某字段在图片中未出现或模糊无法辨认，请返回空字符串 ""。
      2) 仔细辨认邮箱字符（注意区分字母 l 与数字 1、字母 O 与数字 0、下划线 _ 与连字符 -），去除多余空格。
      3) 请仅输出一个合法 JSON 对象，不要输出 ```json 代码块，也不要输出任何解释文字：
      {"candidateName":"","email":"","location":"","phone":""}
      """ + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;

  private static final String USER_PROMPT = """
      请从这张简历图片中提取候选人信息并严格只返回 JSON 对象：
      - candidateName：候选人姓名（仅保留姓名或称呼本身，去掉“姓名：”等标签）
      - email：候选人电子邮箱地址（用于发送面试邀请邮件，未出现则返回 ""）
      - location：候选人现居城市或联系地址（如“上海·浦东新区”“杭州市滨江区”，未出现则返回 ""）
      - phone：候选人联系电话/手机号（未出现则返回 ""）
      """;

  private final LlmProviderRegistry llmProviderRegistry;
  private final RecruitmentProperties properties;
  private final ObjectMapper objectMapper;

  public BatchResumeImageExtractDTO extractBatch(List<MultipartFile> files) {
    List<MultipartFile> nonNullFiles = files == null
        ? List.of()
        : files.stream().filter(Objects::nonNull).toList();
    if (nonNullFiles.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请至少上传一张简历图片");
    }
    int maxBatch = Math.max(1, properties.getMaxResumeImagesPerBatch());
    if (nonNullFiles.size() > maxBatch) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "单次最多批量上传 " + maxBatch + " 张简历图片");
    }

    Semaphore semaphore = new Semaphore(MAX_CONCURRENT_VISION_CALLS);
    List<ResumeImageExtractItemDTO> items;
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<ResumeImageExtractItemDTO>> futures = new ArrayList<>(nonNullFiles.size());
      for (int i = 0; i < nonNullFiles.size(); i++) {
        final int index = i;
        final MultipartFile file = nonNullFiles.get(i);
        futures.add(CompletableFuture.supplyAsync(
            () -> extractSingleGuarded(file, index, semaphore), executor));
      }
      items = futures.stream().map(CompletableFuture::join).toList();
    }

    int validCount = (int) items.stream().filter(ResumeImageExtractItemDTO::emailValid).count();
    log.info("批量简历图片识别完成: total={}, validEmails={}", items.size(), validCount);
    return new BatchResumeImageExtractDTO(items.size(), validCount, items);
  }

  private ResumeImageExtractItemDTO extractSingleGuarded(MultipartFile file, int index, Semaphore semaphore) {
    String filename = resolveFilename(file.getOriginalFilename(), index);
    if (file.isEmpty()) {
      return new ResumeImageExtractItemDTO(filename, false, null, null, false, null, null, "图片文件为空");
    }
    long maxBytes = properties.getMaxResumeImageSizeBytes() > 0
        ? properties.getMaxResumeImageSizeBytes()
        : 10L * 1024 * 1024;
    if (file.getSize() > maxBytes) {
      long maxMb = Math.max(1L, maxBytes / (1024 * 1024));
      return new ResumeImageExtractItemDTO(
          filename, false, null, null, false, null, null, "单张图片不能超过 " + maxMb + "MB");
    }

    byte[] rawBytes;
    try {
      rawBytes = file.getBytes();
    } catch (Exception e) {
      log.warn("读取上传简历图片失败: filename={}, error={}", filename, ErrorLogSanitizer.summarize(e));
      return new ResumeImageExtractItemDTO(filename, false, null, null, false, null, null, "读取图片内容失败");
    }

    if (rawBytes.length == 0) {
      return new ResumeImageExtractItemDTO(filename, false, null, null, false, null, null, "图片文件为空");
    }
    if (isDocumentHeader(rawBytes)) {
      return new ResumeImageExtractItemDTO(
          filename, false, null, null, false, null, null,
          "检测到文档格式而非图片，请上传简历截图或图片（PNG / JPG / WEBP / GIF / BMP）");
    }

    MimeType detectedMime = resolveImageMimeType(rawBytes, file.getContentType(), filename);
    if (detectedMime == null) {
      return new ResumeImageExtractItemDTO(
          filename, false, null, null, false, null, null,
          "仅支持 PNG / JPG / WEBP / GIF / BMP 格式的简历图片");
    }

    PreparedImage prepared = prepareImagePayload(rawBytes, detectedMime);

    boolean acquired = false;
    try {
      semaphore.acquire();
      acquired = true;
      String modelOutput = invokeVisionModel(prepared.mimeType(), prepared.bytes());
      return parseModelOutput(filename, modelOutput);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return new ResumeImageExtractItemDTO(filename, false, null, null, false, null, null, "识别任务被中断，请重试");
    } catch (Exception e) {
      log.warn("简历图片 AI 识别失败: filename={}, error={}",
          filename, ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
      return new ResumeImageExtractItemDTO(
          filename, false, null, null, false, null, null, "AI 识别失败，请稍后重试或手动填写邮箱");
    } finally {
      if (acquired) {
        semaphore.release();
      }
    }
  }

  String invokeVisionModel(MimeType mimeType, byte[] imageBytes) {
    String configuredProvider = blankToNull(properties.getVisionProvider());
    String configuredModel = blankToNull(properties.getVisionModel());
    ChatClient primaryClient = llmProviderRegistry.getPlainChatClient(configuredProvider);

    OpenAiChatOptions.Builder primaryOptions = OpenAiChatOptions.builder().temperature(0.1);
    if (configuredModel != null) {
      primaryOptions.model(configuredModel);
    }

    try {
      return callChatClientWithImage(primaryClient, primaryOptions, mimeType, imageBytes);
    } catch (Exception primaryError) {
      if (configuredModel != null) {
        throw primaryError;
      }
      log.warn("默认模型识别简历图片失败，尝试回退 DashScope 视觉模型 {}: error={}",
          FALLBACK_VISION_MODEL, ErrorLogSanitizer.summarize(primaryError));
      try {
        ChatClient fallbackClient = llmProviderRegistry.getPlainChatClient(DASHSCOPE_PROVIDER_ID);
        OpenAiChatOptions.Builder fallbackOptions = OpenAiChatOptions.builder()
            .model(FALLBACK_VISION_MODEL)
            .temperature(0.1);
        return callChatClientWithImage(fallbackClient, fallbackOptions, mimeType, imageBytes);
      } catch (Exception fallbackError) {
        log.warn("回退视觉模型 {} 仍失败: error={}",
            FALLBACK_VISION_MODEL, ErrorLogSanitizer.summarize(fallbackError));
        throw primaryError;
      }
    }
  }

  private String callChatClientWithImage(ChatClient client, OpenAiChatOptions.Builder options,
                                         MimeType mimeType, byte[] imageBytes) {
    ByteArrayResource resource = new ByteArrayResource(imageBytes);
    String content = client.prompt()
        .system(SYSTEM_PROMPT)
        .user(u -> u.text(USER_PROMPT).media(mimeType, resource))
        .options(options)
        .call()
        .content();
    return content == null ? "" : content;
  }

  ResumeImageExtractItemDTO parseModelOutput(String filename, String rawContent) {
    String cleaned = stripCodeFence(rawContent);
    String candidateName = null;
    String rawEmail = null;
    String location = null;
    String phone = null;

    String jsonCandidate = extractFirstJsonObject(cleaned);
    if (jsonCandidate != null) {
      try {
        JsonNode root = objectMapper.readTree(jsonCandidate);
        candidateName = readTextField(root, "candidateName", "name");
        rawEmail = readTextField(root, "email", "mail", "emailAddress");
        location = readTextField(root, "location", "address", "city");
        phone = readTextField(root, "phone", "mobile");
      } catch (Exception e) {
        log.debug("简历图片识别输出 JSON 解析回退正则提取: filename={}, error={}",
            filename, ErrorLogSanitizer.summarize(e));
      }
    }

    if ((rawEmail == null || rawEmail.isBlank()) && cleaned != null && !cleaned.isBlank()) {
      Matcher matcher = EMAIL_FINDER_PATTERN.matcher(normalizeEmailPunctuation(cleaned));
      if (matcher.find()) {
        rawEmail = matcher.group();
      }
    }

    String normalizedName = normalizeName(candidateName);
    String normalizedEmail = normalizeEmail(rawEmail);
    String normalizedLocation = normalizeLocation(location);
    String normalizedPhone = normalizePhone(phone);

    boolean emailValid = normalizedEmail != null && RecruitmentInvitationService.isValidEmail(normalizedEmail);
    String error = null;
    if (normalizedEmail == null) {
      error = "图片中未识别到邮箱地址，可手动补填";
    } else if (!emailValid) {
      error = "识别到的邮箱格式有误，请核对修正";
    }

    return new ResumeImageExtractItemDTO(
        filename,
        true,
        normalizedName,
        normalizedEmail,
        emailValid,
        normalizedLocation,
        normalizedPhone,
        error
    );
  }

  private record PreparedImage(MimeType mimeType, byte[] bytes) {
  }

  private PreparedImage prepareImagePayload(byte[] rawBytes, MimeType detectedMime) {
    if (rawBytes.length <= COMPRESS_THRESHOLD_BYTES) {
      return new PreparedImage(detectedMime, rawBytes);
    }
    try {
      BufferedImage src = ImageIO.read(new ByteArrayInputStream(rawBytes));
      if (src == null) {
        return new PreparedImage(detectedMime, rawBytes);
      }
      int width = src.getWidth();
      int height = src.getHeight();
      double scale = Math.min(1.0, (double) MAX_IMAGE_DIMENSION / Math.max(width, height));
      int targetWidth = Math.max(1, (int) Math.round(width * scale));
      int targetHeight = Math.max(1, (int) Math.round(height * scale));

      BufferedImage rgbImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
      Graphics2D g2d = rgbImage.createGraphics();
      try {
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setColor(java.awt.Color.WHITE);
        g2d.fillRect(0, 0, targetWidth, targetHeight);
        g2d.drawImage(src, 0, 0, targetWidth, targetHeight, null);
      } finally {
        g2d.dispose();
      }

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (ImageIO.write(rgbImage, "jpg", out) && out.size() > 0 && out.size() < rawBytes.length) {
        return new PreparedImage(MimeType.valueOf("image/jpeg"), out.toByteArray());
      }
    } catch (Throwable ignored) {
      // 无头环境或特殊编码无法缩放时保留原始图像字节
    }
    return new PreparedImage(detectedMime, rawBytes);
  }

  static MimeType resolveImageMimeType(byte[] bytes, String contentType, String filename) {
    if (bytes != null) {
      if (bytes.length >= 8
          && (bytes[0] & 0xFF) == 0x89
          && bytes[1] == 0x50
          && bytes[2] == 0x4E
          && bytes[3] == 0x47) {
        return MimeType.valueOf("image/png");
      }
      if (bytes.length >= 3
          && (bytes[0] & 0xFF) == 0xFF
          && (bytes[1] & 0xFF) == 0xD8
          && (bytes[2] & 0xFF) == 0xFF) {
        return MimeType.valueOf("image/jpeg");
      }
      if (bytes.length >= 6
          && bytes[0] == 'G'
          && bytes[1] == 'I'
          && bytes[2] == 'F'
          && bytes[3] == '8') {
        return MimeType.valueOf("image/gif");
      }
      if (bytes.length >= 12
          && bytes[0] == 'R'
          && bytes[1] == 'I'
          && bytes[2] == 'F'
          && bytes[3] == 'F'
          && bytes[8] == 'W'
          && bytes[9] == 'E'
          && bytes[10] == 'B'
          && bytes[11] == 'P') {
        return MimeType.valueOf("image/webp");
      }
      if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') {
        return MimeType.valueOf("image/bmp");
      }
    }

    if (contentType != null && !contentType.isBlank()) {
      String ct = contentType.trim().toLowerCase(Locale.ROOT);
      int semi = ct.indexOf(';');
      if (semi > 0) {
        ct = ct.substring(0, semi).trim();
      }
      switch (ct) {
        case "image/png" -> {
          return MimeType.valueOf("image/png");
        }
        case "image/jpeg", "image/jpg" -> {
          return MimeType.valueOf("image/jpeg");
        }
        case "image/webp" -> {
          return MimeType.valueOf("image/webp");
        }
        case "image/gif" -> {
          return MimeType.valueOf("image/gif");
        }
        case "image/bmp", "image/x-ms-bmp" -> {
          return MimeType.valueOf("image/bmp");
        }
        default -> {
          // 继续根据扩展名判断
        }
      }
    }

    if (filename != null) {
      String lower = filename.toLowerCase(Locale.ROOT);
      if (lower.endsWith(".png")) {
        return MimeType.valueOf("image/png");
      }
      if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
        return MimeType.valueOf("image/jpeg");
      }
      if (lower.endsWith(".webp")) {
        return MimeType.valueOf("image/webp");
      }
      if (lower.endsWith(".gif")) {
        return MimeType.valueOf("image/gif");
      }
      if (lower.endsWith(".bmp")) {
        return MimeType.valueOf("image/bmp");
      }
    }
    return null;
  }

  private static boolean isDocumentHeader(byte[] bytes) {
    if (bytes.length >= 5) {
      String prefix = new String(bytes, 0, 5, StandardCharsets.US_ASCII);
      if ("%PDF-".equals(prefix)) {
        return true;
      }
    }
    return bytes.length >= 4
        && bytes[0] == 'P'
        && bytes[1] == 'K'
        && bytes[2] == 0x03
        && bytes[3] == 0x04;
  }

  private static String resolveFilename(String originalFilename, int index) {
    if (originalFilename == null || originalFilename.isBlank()) {
      return "resume-image-" + (index + 1) + ".png";
    }
    String clean = originalFilename.replace('\\', '/');
    int slash = clean.lastIndexOf('/');
    if (slash >= 0 && slash + 1 < clean.length()) {
      clean = clean.substring(slash + 1);
    }
    return clean.length() > 160 ? clean.substring(0, 160) : clean;
  }

  private static String stripCodeFence(String text) {
    if (text == null) {
      return "";
    }
    String trimmed = text.trim();
    return CODE_FENCE_PATTERN.matcher(trimmed).replaceAll("").trim();
  }

  private static String extractFirstJsonObject(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    if (start >= 0 && end > start) {
      return text.substring(start, end + 1);
    }
    return null;
  }

  private static String readTextField(JsonNode root, String... fieldNames) {
    for (String field : fieldNames) {
      JsonNode node = root.get(field);
      if (node != null && !node.isNull()) {
        String val = node.asString("").trim();
        if (!val.isEmpty() && !"null".equalsIgnoreCase(val) && !"无".equals(val) && !"未提及".equals(val)) {
          return val;
        }
      }
    }
    return null;
  }

  private static String normalizeName(String raw) {
    if (raw == null) {
      return null;
    }
    String cleaned = NAME_PREFIX_PATTERN.matcher(raw.trim()).replaceFirst("")
        .replaceAll("[\\r\\n\\t]+", " ")
        .trim();
    if (cleaned.isEmpty() || "未知".equals(cleaned) || "暂无".equals(cleaned)) {
      return null;
    }
    return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
  }

  private static String normalizeEmailPunctuation(String raw) {
    return raw.replace('＠', '@')
        .replace('．', '.')
        .replace('。', '.');
  }

  private static String normalizeEmail(String raw) {
    if (raw == null) {
      return null;
    }
    String cleaned = EMAIL_PREFIX_PATTERN.matcher(raw.trim()).replaceFirst("");
    cleaned = normalizeEmailPunctuation(cleaned).replaceAll("\\s+", "").trim();
    if (cleaned.isEmpty()) {
      return null;
    }
    Matcher matcher = EMAIL_FINDER_PATTERN.matcher(cleaned);
    if (matcher.find()) {
      return matcher.group().toLowerCase(Locale.ROOT);
    }
    return cleaned.toLowerCase(Locale.ROOT);
  }

  private static String normalizeLocation(String raw) {
    if (raw == null) {
      return null;
    }
    String cleaned = LOCATION_PREFIX_PATTERN.matcher(raw.trim()).replaceFirst("")
        .replaceAll("[\\r\\n\\t]+", " ")
        .trim();
    if (cleaned.isEmpty() || "未知".equals(cleaned) || "暂无".equals(cleaned)) {
      return null;
    }
    return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
  }

  private static String normalizePhone(String raw) {
    if (raw == null) {
      return null;
    }
    String cleaned = raw.replaceAll("[\\r\\n\\t]+", " ").trim();
    if (cleaned.isEmpty() || "未知".equals(cleaned) || "暂无".equals(cleaned)) {
      return null;
    }
    return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
