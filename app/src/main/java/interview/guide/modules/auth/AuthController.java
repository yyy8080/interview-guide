package interview.guide.modules.auth;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.common.security.CurrentAdmin;
import interview.guide.modules.auth.model.AdminUserDTO;
import interview.guide.modules.auth.model.ChangePasswordRequest;
import interview.guide.modules.auth.model.LoginRequest;
import interview.guide.modules.auth.model.LoginResponse;
import interview.guide.modules.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台认证接口
 */
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  @PostMapping("/login")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
    return Result.success(authService.login(request));
  }

  @GetMapping("/me")
  public Result<AdminUserDTO> me() {
    return Result.success(authService.me(CurrentAdmin.id()));
  }

  @PutMapping("/password")
  public Result<LoginResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
    return Result.success(authService.changePassword(CurrentAdmin.id(), request));
  }
}
