package interview.guide.modules.recruitment.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.recruitment.model.CandidateAnswerRequest;
import interview.guide.modules.recruitment.model.CandidateAnswerResponse;
import interview.guide.modules.recruitment.model.CandidateApplicationDTO;
import interview.guide.modules.recruitment.model.CandidateInterviewDTO;
import interview.guide.modules.recruitment.model.CandidateJobInvitationStatusDTO;
import interview.guide.modules.recruitment.model.CandidateProfileDTO;
import interview.guide.modules.recruitment.model.CandidateWrittenTestDTO;
import interview.guide.modules.recruitment.model.OfflineRescheduleRequest;
import interview.guide.modules.recruitment.model.ProctorEventRequest;
import interview.guide.modules.recruitment.model.StartInterviewRequest;
import interview.guide.modules.recruitment.model.StartWrittenTestRequest;
import interview.guide.modules.recruitment.model.WrittenAnswersRequest;
import interview.guide.modules.recruitment.service.CandidateApplicationService;
import interview.guide.modules.recruitment.service.CandidateAuthService;
import interview.guide.modules.recruitment.service.CandidateInterviewService;
import interview.guide.modules.recruitment.service.CandidateWrittenTestService;
import interview.guide.modules.recruitment.service.RecruitmentInvitationService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 候选人门户接口：凭邮箱验证后签发的候选人令牌（请求头 X-Candidate-Token）访问
 */
@RestController
@RequestMapping("/api/candidate")
@RequiredArgsConstructor
public class CandidatePortalController {

  private final CandidateAuthService candidateAuthService;
  private final CandidateApplicationService applicationService;
  private final CandidateInterviewService interviewService;
  private final CandidateWrittenTestService writtenTestService;
  private final RecruitmentInvitationService invitationService;

  @GetMapping("/me")
  public Result<CandidateProfileDTO> me(@RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false)
                                        String token) {
    return Result.success(candidateAuthService.me(candidateAuthService.requireCandidateId(token)));
  }

  // ========== 投递 ==========

  @GetMapping("/jobs/{jobId}/invitation")
  public Result<CandidateJobInvitationStatusDTO> getJobInvitation(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long jobId) {
    Long candidateId = candidateAuthService.requireCandidateId(token);
    return Result.success(invitationService.checkCandidateJobStatus(jobId, candidateId));
  }

  @PostMapping(value = "/jobs/{jobId}/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public Result<CandidateApplicationDTO> apply(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long jobId,
      @RequestParam("file") MultipartFile file,
      @RequestParam("name") String name,
      @RequestParam(value = "phone", required = false) String phone,
      @RequestParam(value = "candidateLevel", required = false) String candidateLevel,
      @RequestParam(value = "consent", defaultValue = "false") boolean consent) {
    Long candidateId = candidateAuthService.requireCandidateId(token);
    return Result.success(applicationService.apply(jobId, candidateId, name, phone, consent, file, candidateLevel));
  }

  @GetMapping("/applications")
  public Result<List<CandidateApplicationDTO>> listApplications(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token) {
    return Result.success(applicationService.listMine(candidateAuthService.requireCandidateId(token)));
  }

  @GetMapping("/applications/{id}")
  public Result<CandidateApplicationDTO> getApplication(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id) {
    return Result.success(applicationService.getMine(candidateAuthService.requireCandidateId(token), id));
  }

  @PostMapping(value = "/applications/{id}/resubmit-resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public Result<CandidateApplicationDTO> resubmitResume(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "candidateLevel", required = false) String candidateLevel) {
    Long candidateId = candidateAuthService.requireCandidateId(token);
    return Result.success(applicationService.resubmitResume(candidateId, id, file, candidateLevel));
  }

  // ========== 在线面试 ==========

  @PostMapping("/applications/{id}/interview/start")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public Result<CandidateInterviewDTO> startInterview(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id,
      @Valid @RequestBody StartInterviewRequest request) {
    return Result.success(interviewService.start(
        candidateAuthService.requireCandidateId(token),
        id,
        request.mode(),
        request.candidateLevel(),
        request.aiExperience(),
        request.specialties()));
  }

  @GetMapping("/applications/{id}/interview")
  public Result<CandidateInterviewDTO> getInterview(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id) {
    return Result.success(interviewService.getState(candidateAuthService.requireCandidateId(token), id));
  }

  @PostMapping("/applications/{id}/interview/answer")
  public Result<CandidateAnswerResponse> answer(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id,
      @Valid @RequestBody CandidateAnswerRequest request) {
    return Result.success(interviewService.answer(candidateAuthService.requireCandidateId(token), id, request));
  }

  @PostMapping("/applications/{id}/interview/complete")
  public Result<CandidateInterviewDTO> complete(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id) {
    return Result.success(interviewService.complete(candidateAuthService.requireCandidateId(token), id));
  }

  @PostMapping("/applications/{id}/interview/voice/end")
  public Result<CandidateInterviewDTO> endVoice(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id) {
    return Result.success(interviewService.endVoice(candidateAuthService.requireCandidateId(token), id));
  }

  @PostMapping("/applications/{id}/proctor-events")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 120, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public Result<Void> recordProctor(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id,
      @Valid @RequestBody ProctorEventRequest request) {
    interviewService.recordProctor(candidateAuthService.requireCandidateId(token), id, request);
    return Result.success(null);
  }

  // ========== 在线笔试（选择题 / 填空题） ==========

  @GetMapping("/applications/{id}/written-test")
  public Result<CandidateWrittenTestDTO> getWrittenTest(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id) {
    return Result.success(writtenTestService.getState(candidateAuthService.requireCandidateId(token), id));
  }

  @PostMapping("/applications/{id}/written-test/start")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public Result<CandidateWrittenTestDTO> startWrittenTest(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id,
      @Valid @RequestBody(required = false) StartWrittenTestRequest request) {
    return Result.success(writtenTestService.start(
        candidateAuthService.requireCandidateId(token),
        id,
        request != null ? request.candidateLevel() : null,
        request != null ? request.aiExperience() : null,
        request != null ? request.specialties() : null));
  }

  @PutMapping("/applications/{id}/written-test/answers")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 120, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public Result<Void> saveWrittenAnswers(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id,
      @Valid @RequestBody WrittenAnswersRequest request) {
    writtenTestService.saveAnswers(candidateAuthService.requireCandidateId(token), id, request);
    return Result.success(null);
  }

  @PostMapping("/applications/{id}/written-test/submit")
  public Result<CandidateWrittenTestDTO> submitWrittenTest(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id,
      @Valid @RequestBody WrittenAnswersRequest request) {
    return Result.success(writtenTestService.submit(candidateAuthService.requireCandidateId(token), id, request));
  }

  // ========== 线下面试 ==========

  @PostMapping("/applications/{id}/offline/confirm")
  public Result<CandidateApplicationDTO> confirmOffline(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id) {
    return Result.success(applicationService.confirmOffline(candidateAuthService.requireCandidateId(token), id));
  }

  @PostMapping("/applications/{id}/offline/reschedule")
  public Result<CandidateApplicationDTO> requestReschedule(
      @RequestHeader(value = CandidateAuthService.TOKEN_HEADER, required = false) String token,
      @PathVariable Long id,
      @Valid @RequestBody OfflineRescheduleRequest request) {
    return Result.success(applicationService.requestReschedule(candidateAuthService.requireCandidateId(token), id,
        request.note()));
  }
}
