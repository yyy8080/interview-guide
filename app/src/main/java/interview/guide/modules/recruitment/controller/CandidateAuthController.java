package interview.guide.modules.recruitment.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.recruitment.model.CandidateLoginResponse;
import interview.guide.modules.recruitment.model.SendCodeRequest;
import interview.guide.modules.recruitment.model.VerifyCodeRequest;
import interview.guide.modules.recruitment.service.CandidateAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 候选人邮箱验证码登录（公开接口）
 */
@RestController
@RequestMapping("/api/public/candidate-auth")
@RequiredArgsConstructor
public class CandidateAuthController {

  private final CandidateAuthService candidateAuthService;

  @PostMapping("/send-code")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public Result<Void> sendCode(@Valid @RequestBody SendCodeRequest request) {
    candidateAuthService.sendCode(request.email());
    return Result.success(null);
  }

  @PostMapping("/verify")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public Result<CandidateLoginResponse> verify(@Valid @RequestBody VerifyCodeRequest request) {
    return Result.success(candidateAuthService.verify(request.email(), request.code()));
  }
}
