package interview.guide.modules.auth.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理员更新后台账号请求（不含密码）
 */
public record UpdateAdminUserRequest(
    @Size(max = 64, message = "显示名称过长")
    String displayName,

    @Email(message = "邮箱格式不正确")
    @Size(max = 255, message = "邮箱过长")
    String email,

    @NotNull(message = "角色不能为空")
    AdminRole role,

    @NotNull(message = "启用状态不能为空")
    Boolean enabled
) {}
