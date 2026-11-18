package interview.guide.modules.recruitment.controller;

import interview.guide.modules.recruitment.service.CandidateInterviewService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 候选人语音面试 WebSocket 握手校验：查询参数 candidate_token 必须属于该语音会话所在的进行中投递
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateVoiceHandshakeInterceptor implements HandshakeInterceptor {

  public static final String TOKEN_PARAM = "candidate_token";

  private final CandidateInterviewService interviewService;

  @Override
  public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                 WebSocketHandler wsHandler, Map<String, Object> attributes) {
    String path = request.getURI().getPath();
    Long sessionId = parseSessionId(path);
    String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst(TOKEN_PARAM);
    if (interviewService.canAccessVoice(token, sessionId)) {
      return true;
    }
    log.warn("拒绝候选人语音面试连接: path={}", path);
    response.setStatusCode(HttpStatus.FORBIDDEN);
    return false;
  }

  @Override
  public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                             WebSocketHandler wsHandler, Exception exception) {
    // 无需处理
  }

  static Long parseSessionId(String path) {
    if (path == null) {
      return null;
    }
    try {
      return Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
