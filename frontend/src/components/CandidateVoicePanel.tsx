import { useCallback, useEffect, useRef, useState } from 'react';
import type { ClipboardEvent as ReactClipboardEvent } from 'react';
import { Loader2, PhoneOff, Send, Volume2 } from 'lucide-react';
import { API_BASE_URL } from '../api/request';
import { VoiceInterviewWebSocket } from '../api/voiceInterview';
import AudioRecorder from './AudioRecorder';
import { getValidCandidateToken } from '../utils/candidateStorage';
import { buildVoiceWebSocketUrl } from '../utils/voiceWebSocketUrl';
import type { ProctorEventPayload } from '../types/recruitment';

/** 面试官提问结束后超过该时长才开口，记录为应答延迟（单次不计入风险，多次才提示） */
const SLOW_RESPONSE_MS = 15_000;

interface ChatTurn {
  id: string;
  role: 'ai' | 'user';
  text: string;
}

interface CandidateVoicePanelProps {
  voiceSessionId: number;
  onEndInterview: () => Promise<void>;
  ending: boolean;
  /** 作答行为上报（无感记录） */
  onProctor?: (event: ProctorEventPayload) => void;
}

export default function CandidateVoicePanel({
  voiceSessionId,
  onEndInterview,
  ending,
  onProctor,
}: CandidateVoicePanelProps) {
  const [status, setStatus] = useState<'connecting' | 'connected' | 'disconnected'>('connecting');
  const [isRecording, setIsRecording] = useState(false);
  const [aiSpeaking, setAiSpeaking] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [aiLiveText, setAiLiveText] = useState('');
  const [userText, setUserText] = useState('');
  const [messages, setMessages] = useState<ChatTurn[]>([]);
  const [error, setError] = useState<string | null>(null);
  // 本轮是否已识别到语音：必须开口作答才能提交，文本框仅用于修正识别错别字
  const [hasHeard, setHasHeard] = useState(false);
  const [pasteHint, setPasteHint] = useState(false);

  const wsRef = useRef<VoiceInterviewWebSocket | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const chunkQueueRef = useRef<AudioBuffer[]>([]);
  const isChunkPlayingRef = useRef(false);
  const aiTextRef = useRef('');
  const lastCommittedAiRef = useRef('');
  const submittingRef = useRef(false);
  const aiSpeakingRef = useRef(false);
  const aiFinishedAtRef = useRef<number | null>(null);
  const onProctorRef = useRef(onProctor);

  useEffect(() => {
    onProctorRef.current = onProctor;
  }, [onProctor]);

  const setSubmittingSync = useCallback((v: boolean) => {
    submittingRef.current = v;
    setSubmitting(v);
  }, []);

  const setAiSpeakingSync = useCallback((v: boolean) => {
    if (aiSpeakingRef.current && !v) {
      aiFinishedAtRef.current = Date.now();
    }
    aiSpeakingRef.current = v;
    setAiSpeaking(v);
  }, []);

  const commitAi = useCallback((raw: string) => {
    const text = raw.trim();
    if (!text || text === lastCommittedAiRef.current) return;
    lastCommittedAiRef.current = text;
    setMessages(prev => [...prev, { id: `ai-${Date.now()}-${Math.random()}`, role: 'ai', text }]);
    setAiLiveText('');
  }, []);

  const getAudioContext = useCallback(() => {
    if (!audioContextRef.current || audioContextRef.current.state === 'closed') {
      audioContextRef.current = new AudioContext({ sampleRate: 24000 });
    }
    return audioContextRef.current;
  }, []);

  const playNextChunk = useCallback(() => {
    if (chunkQueueRef.current.length === 0) {
      isChunkPlayingRef.current = false;
      setAiSpeakingSync(false);
      setSubmittingSync(false);
      if (aiTextRef.current.trim()) {
        commitAi(aiTextRef.current);
      }
      return;
    }
    isChunkPlayingRef.current = true;
    const ctx = getAudioContext();
    if (ctx.state === 'suspended') {
      ctx.resume().catch(() => {});
    }
    const buffer = chunkQueueRef.current.shift()!;
    const source = ctx.createBufferSource();
    source.buffer = buffer;
    source.connect(ctx.destination);
    source.onended = () => playNextChunk();
    source.start(0);
  }, [commitAi, getAudioContext, setAiSpeakingSync, setSubmittingSync]);

  const enqueueWavChunk = useCallback(
    (base64Wav: string) => {
      try {
        const binary = atob(base64Wav);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) {
          bytes[i] = binary.charCodeAt(i);
        }
        const pcmOffset = 44;
        if (bytes.length <= pcmOffset) return;
        const pcm = new Int16Array(bytes.buffer, pcmOffset, Math.floor((bytes.length - pcmOffset) / 2));
        const float32 = new Float32Array(pcm.length);
        for (let i = 0; i < pcm.length; i++) {
          float32[i] = pcm[i] / 32768.0;
        }
        const ctx = getAudioContext();
        const audioBuffer = ctx.createBuffer(1, float32.length, 24000);
        audioBuffer.getChannelData(0).set(float32);
        chunkQueueRef.current.push(audioBuffer);
        setAiSpeakingSync(true);
        if (!isChunkPlayingRef.current) {
          playNextChunk();
        }
      } catch {
        // 音频块解码失败时保留文字展示
      }
    },
    [getAudioContext, playNextChunk, setAiSpeakingSync],
  );

  useEffect(() => {
    const token = getValidCandidateToken();
    const wsUrl = buildVoiceWebSocketUrl(
      voiceSessionId,
      API_BASE_URL,
      window.location.href,
      token,
      {
        pathPrefix: '/ws/candidate/voice-interview',
        tokenParam: 'candidate_token',
      },
    );

    const ws = new VoiceInterviewWebSocket(voiceSessionId, wsUrl, {
      onOpen: () => {
        setStatus('connected');
        setError(null);
      },
      onSubtitle: (text, isFinal) => {
        if (!isFinal && (submittingRef.current || aiSpeakingRef.current)) {
          return;
        }
        if (text.trim() && !isFinal) {
          setHasHeard(true);
          const finishedAt = aiFinishedAtRef.current;
          if (finishedAt != null) {
            aiFinishedAtRef.current = null;
            const latency = Date.now() - finishedAt;
            if (latency >= SLOW_RESPONSE_MS) {
              onProctorRef.current?.({ type: 'VOICE_SLOW_RESPONSE', durationMs: latency });
            }
          }
        }
        if (isFinal && text.trim()) {
          setMessages(prev => {
            const last = prev[prev.length - 1];
            if (last?.role === 'user' && last.text === text.trim()) return prev;
            return [...prev, { id: `u-${Date.now()}`, role: 'user', text: text.trim() }];
          });
          setUserText('');
        } else {
          setUserText(text);
        }
      },
      onTextResponse: (text, isFinal) => {
        const trimmed = (text || '').trim();
        if (!trimmed) return;
        aiTextRef.current = trimmed;
        setAiLiveText(trimmed);
        setAiSpeakingSync(true);
        if (isFinal) {
          window.setTimeout(() => {
            if (!isChunkPlayingRef.current) {
              commitAi(trimmed);
              setAiSpeakingSync(false);
              setSubmittingSync(false);
            }
          }, 2500);
        }
      },
      onAudioResponse: (audioData, text) => {
        const trimmed = (text || '').trim();
        if (trimmed) {
          aiTextRef.current = trimmed;
          setAiLiveText(trimmed);
        }
        if (audioData && audioRef.current) {
          setAiSpeakingSync(true);
          audioRef.current.src = `data:audio/mp3;base64,${audioData}`;
          audioRef.current.play().catch(() => {
            commitAi(trimmed);
            setAiSpeakingSync(false);
            setSubmittingSync(false);
          });
        } else if (trimmed) {
          commitAi(trimmed);
          setAiSpeakingSync(false);
          setSubmittingSync(false);
        }
      },
      onAudioChunk: data => enqueueWavChunk(data),
      onErrorMessage: msg => {
        setError(msg || '语音服务提示异常');
        setSubmittingSync(false);
      },
      onClose: () => setStatus('disconnected'),
      onError: () => {
        setStatus('disconnected');
        setError('语音连接断开，请检查网络或刷新页面重试');
      },
    });

    wsRef.current = ws;
    ws.connect();

    return () => {
      ws.disconnect();
      audioContextRef.current?.close().catch(() => {});
    };
  }, [voiceSessionId, commitAi, enqueueWavChunk, setAiSpeakingSync, setSubmittingSync]);

  const handleSendText = () => {
    const trimmed = userText.trim();
    if (!trimmed || !hasHeard || !wsRef.current?.isConnected() || submitting || aiSpeaking) return;
    setIsRecording(false);
    setHasHeard(false);
    setPasteHint(false);
    setSubmittingSync(true);
    setMessages(prev => [...prev, { id: `u-${Date.now()}`, role: 'user', text: trimmed }]);
    setUserText('');
    wsRef.current.sendControl('submit', { text: trimmed });
  };

  /** 语音作答框不支持粘贴（语音面试需口头作答），拦截并无感记录实际粘贴文本 */
  const handleVoicePaste = (e: ReactClipboardEvent<HTMLTextAreaElement>) => {
    e.preventDefault();
    setPasteHint(true);
    const rawText = e.clipboardData?.getData('text') ?? '';
    const chars = rawText.length;
    onProctorRef.current?.({
      type: 'PASTE',
      chars,
      pastedText: rawText ? rawText.slice(0, 4000) : undefined,
      detail: '语音作答框粘贴（已拦截）',
    });
  };

  const handleFinish = async () => {
    setIsRecording(false);
    wsRef.current?.sendControl('end_interview');
    wsRef.current?.disconnect();
    await onEndInterview();
  };

  return (
    <div className="space-y-4">
      <audio
        ref={audioRef}
        onEnded={() => {
          commitAi(aiTextRef.current);
          setAiSpeakingSync(false);
          setSubmittingSync(false);
        }}
      />

      <div className="dark-card flex flex-wrap items-center justify-between gap-3 p-4">
        <div className="flex items-center gap-2 text-sm">
          <span
            className={`h-2.5 w-2.5 rounded-full ${
              status === 'connected'
                ? 'bg-emerald-500'
                : status === 'connecting'
                ? 'bg-amber-400 animate-pulse'
                : 'bg-rose-500'
            }`}
          />
          <span className="text-slate-700 dark:text-slate-200">
            {status === 'connected' ? '实时语音已连接' : status === 'connecting' ? '正在连接语音面试官…' : '语音连接已断开'}
          </span>
          {aiSpeaking && (
            <span className="flex items-center gap-1 text-xs text-primary-600 dark:text-primary-400">
              <Volume2 className="h-3.5 w-3.5 animate-pulse" />
              面试官正在提问…
            </span>
          )}
        </div>

        <button
          type="button"
          disabled={ending}
          onClick={handleFinish}
          className="flex items-center gap-1.5 rounded-xl bg-rose-600 px-4 py-1.5 text-xs font-medium text-white hover:bg-rose-700 disabled:opacity-60"
        >
          {ending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <PhoneOff className="h-3.5 w-3.5" />}
          结束面试并交卷
        </button>
      </div>

      {error && (
        <div className="rounded-xl bg-amber-50 px-4 py-2.5 text-xs text-amber-800 dark:bg-amber-900/20 dark:text-amber-200">
          {error}
        </div>
      )}

      {/* 对话记录 */}
      <div className="dark-card max-h-[380px] min-h-[220px] space-y-3 overflow-y-auto p-5">
        {messages.length === 0 && !aiLiveText && (
          <p className="py-10 text-center text-sm text-slate-400">等待 AI 面试官发起开场提问…</p>
        )}
        {messages.map(m => (
          <div
            key={m.id}
            className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}
          >
            <div
              className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm ${
                m.role === 'user'
                  ? 'bg-primary-500 text-white'
                  : 'bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-100'
              }`}
            >
              {m.text}
            </div>
          </div>
        ))}
        {aiLiveText && (
          <div className="flex justify-start">
            <div className="max-w-[85%] rounded-2xl bg-slate-100 px-4 py-2.5 text-sm text-slate-800 dark:bg-slate-800 dark:text-slate-100">
              {aiLiveText}
            </div>
          </div>
        )}
      </div>

      {/* 录音与回答编辑发送 */}
      <div className="dark-card space-y-3 p-5">
        <div className="flex flex-wrap items-center gap-4">
          <AudioRecorder
            isRecording={isRecording}
            disabled={status !== 'connected' || aiSpeaking || submitting}
            onRecordingChange={setIsRecording}
            onAudioData={pcmBase64 => wsRef.current?.sendAudio(pcmBase64)}
          />
          <p className="text-xs text-slate-500 dark:text-slate-400">
            点击麦克风口头作答，识别出的文字会显示在下方，可修正个别错别字后点击「发送回答」
          </p>
        </div>

        <div className="flex gap-2">
          <textarea
            rows={2}
            value={userText}
            onChange={e => setUserText(e.target.value)}
            onPaste={handleVoicePaste}
            disabled={aiSpeaking || submitting || !hasHeard}
            placeholder={hasHeard ? '可修正识别错别字…' : '请先点击麦克风说话，识别内容将显示在这里…'}
            className="flex-1 rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-sm text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-white"
          />
          <button
            type="button"
            disabled={!userText.trim() || !hasHeard || aiSpeaking || submitting || status !== 'connected'}
            onClick={handleSendText}
            className="btn-primary flex items-center gap-1.5 self-end rounded-xl px-5 py-2.5 text-sm disabled:opacity-50"
          >
            {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
            发送回答
          </button>
        </div>
        {pasteHint && (
          <p className="text-xs text-slate-400">语音面试请口头作答，作答框不支持粘贴。</p>
        )}
      </div>
    </div>
  );
}
