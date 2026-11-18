package interview.guide.modules.auth;

import interview.guide.common.result.Result;
import interview.guide.common.security.CurrentAdmin;
import interview.guide.modules.auth.model.AdminUserDTO;
import interview.guide.modules.auth.model.CreateAdminUserRequest;
import interview.guide.modules.auth.model.ResetPasswordRequest;
import interview.guide.modules.auth.model.UpdateAdminUserRequest;
import interview.guide.modules.auth.service.AdminUserService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台账号管理接口（SecurityConfig 限制为 ADMIN）
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

  private final AdminUserService adminUserService;

  @GetMapping
  public Result<List<AdminUserDTO>> list() {
    return Result.success(adminUserService.list());
  }

  @PostMapping
  public Result<AdminUserDTO> create(@Valid @RequestBody CreateAdminUserRequest request) {
    return Result.success(adminUserService.create(request));
  }

  @PutMapping("/{id}")
  public Result<AdminUserDTO> update(@PathVariable Long id, @Valid @RequestBody UpdateAdminUserRequest request) {
    return Result.success(adminUserService.update(CurrentAdmin.id(), id, request));
  }

  @PutMapping("/{id}/password")
  public Result<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
    adminUserService.resetPassword(id, request);
    return Result.success();
  }
}
