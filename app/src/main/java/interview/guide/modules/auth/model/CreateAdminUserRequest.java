package interview.guide.modules.auth.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理员创建后台账号请求
 */
public record CreateAdminUserRequest(
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_.-]{3,64}$", message = "用户名需为 3-64 位字母、数字或 _.-")
    String username,

    @NotBlank(message = "初始密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在 8-64 位之间")
    String password,

    @Size(max = 64, message = "显示名称过长")
    String displayName,

    @Email(message = "邮箱格式不正确")
    @Size(max = 255, message = "邮箱过长")
    String email,

    @NotNull(message = "角色不能为空")
    AdminRole role
) {}
