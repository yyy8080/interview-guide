package interview.guide.modules.recruitment.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * HR 后台发送面试邀请请求（支持单个或批量多个邮箱）
 */
public record CreateInvitationsRequest(
    @NotNull(message = "请选择邀请岗位")
    Long jobId,

    /** 邮箱列表（可与 emailsText 二选一或合并去重） */
    List<String> emails,

    /** 原始邮箱文本（支持换行、逗号、分号或空格分隔多个邮箱） */
    @Size(max = 10000, message = "邮箱列表长度超限")
    String emailsText,

    /** 候选人姓名（单人邀请时选填，用于邮件称呼） */
    @Size(max = 64, message = "姓名不超过 64 字")
    String candidateName,

    /** 邀请有效期天数（为空时取系统默认 inviteExpireDays） */
    @Min(value = 1, message = "有效期至少 1 天")
    @Max(value = 90, message = "有效期不超过 90 天")
    Integer expireDays,

    /** 是否上传简历后跳过 AI 初筛直接进入在线面试 */
    Boolean skipScreening,

    /** 给候选人的补充说明（选填，展示在邀请邮件中） */
    @Size(max = 500, message = "备注不超过 500 字")
    String note
) {
}
