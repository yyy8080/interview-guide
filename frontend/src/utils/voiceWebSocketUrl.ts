export interface VoiceWebSocketUrlOptions {
  /** WebSocket 路径前缀，默认 /ws/voice-interview */
  pathPrefix?: string;
  /** 令牌查询参数名，默认 access_token（候选人侧为 candidate_token） */
  tokenParam?: string;
}

/** 与 REST API 使用同一入口，避免将服务器的 localhost 地址当成浏览器地址。 */
export function buildVoiceWebSocketUrl(
  sessionId: number,
  apiBaseUrl: string,
  pageUrl: string,
  accessToken?: string | null,
  options?: VoiceWebSocketUrlOptions,
): string {
  const apiUrl = new URL(apiBaseUrl || '/', pageUrl);
  const socketUrl = new URL(apiUrl);
  const prefix = (options?.pathPrefix ?? '/ws/voice-interview').replace(/\/+$/, '');
  socketUrl.pathname = `${apiUrl.pathname.replace(/\/+$/, '')}${prefix}/${sessionId}`;
  socketUrl.search = '';
  socketUrl.hash = '';
  socketUrl.protocol = apiUrl.protocol === 'https:' ? 'wss:' : 'ws:';
  // 浏览器 WebSocket 握手无法设置请求头，令牌通过查询参数传递
  if (accessToken) {
    socketUrl.searchParams.set(options?.tokenParam ?? 'access_token', accessToken);
  }
  return socketUrl.toString();
}
