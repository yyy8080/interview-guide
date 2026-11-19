package interview.guide.modules.recruitment.model;

/**
 * 单张简历图片的 AI 联系信息识别结果
 */
public record ResumeImageExtractItemDTO(
    /** 原始图片文件名 */
    String filename,
    /** 是否完成 AI 图像识别调用（文件超限、格式不支持或模型调用异常时为 false） */
    boolean recognized,
    /** 识别出的候选人姓名（未识别到则为 null） */
    String candidateName,
    /** 识别出的候选人邮箱地址（未识别到则为 null） */
    String email,
    /** 邮箱格式是否合法可投递 */
    boolean emailValid,
    /** 识别出的候选人所在城市或联系地址（选填展示，未识别到则为 null） */
    String location,
    /** 识别出的候选人联系电话（选填展示，未识别到则为 null） */
    String phone,
    /** 失败或缺失原因提示（识别成功且邮箱合法时为 null） */
    String error
) {
}
