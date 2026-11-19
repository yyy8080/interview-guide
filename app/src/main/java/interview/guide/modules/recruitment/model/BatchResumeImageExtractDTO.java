package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * 批量上传简历图片自动识别结果
 */
public record BatchResumeImageExtractDTO(
    int totalCount,
    int validEmailCount,
    List<ResumeImageExtractItemDTO> items
) {
}
