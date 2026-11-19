import { useRef, useState } from 'react';
import {
  AlertCircle,
  CheckCircle2,
  ImagePlus,
  Loader2,
  MapPin,
  Phone,
  Sparkles,
  Trash2,
  Upload,
  X,
  ZoomIn,
} from 'lucide-react';

export interface RecognizedCandidateDraft {
  id: string;
  filename: string;
  previewUrl: string | null;
  selected: boolean;
  candidateName: string;
  email: string;
  location: string;
  phone: string;
  recognized: boolean;
  serverError: string | null;
}

const EMAIL_REGEX = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

export function normalizeDraftEmail(raw: string): string {
  return raw
    .replace(/＠/g, '@')
    .replace(/[．。]/g, '.')
    .replace(/\s+/g, '')
    .trim()
    .toLowerCase();
}

export function isDraftEmailValid(raw: string): boolean {
  const normalized = normalizeDraftEmail(raw);
  return normalized.length > 0 && normalized.length <= 255 && EMAIL_REGEX.test(normalized);
}

interface BatchResumeImageInvitePanelProps {
  items: RecognizedCandidateDraft[];
  recognizing: boolean;
  recognizeProgress: string | null;
  maxImages: number;
  disabled?: boolean;
  onUploadFiles: (files: File[]) => void;
  onUpdateItem: (
    id: string,
    patch: Partial<Pick<RecognizedCandidateDraft, 'selected' | 'candidateName' | 'email' | 'location'>>,
  ) => void;
  onRemoveItem: (id: string) => void;
  onClearAll: () => void;
  onToggleSelectAllValid: (checked: boolean) => void;
}

export default function BatchResumeImageInvitePanel({
  items,
  recognizing,
  recognizeProgress,
  maxImages,
  disabled = false,
  onUploadFiles,
  onUpdateItem,
  onRemoveItem,
  onClearAll,
  onToggleSelectAllValid,
}: BatchResumeImageInvitePanelProps) {
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [dragging, setDragging] = useState(false);
  const [previewTarget, setPreviewTarget] = useState<{ url: string; filename: string } | null>(null);

  const remainingSlots = Math.max(0, maxImages - items.length);
  const isFull = remainingSlots === 0;

  const emailCounts = items.reduce<Record<string, number>>((acc, item) => {
    const normalized = normalizeDraftEmail(item.email);
    if (normalized) {
      acc[normalized] = (acc[normalized] ?? 0) + 1;
    }
    return acc;
  }, {});

  const validItems = items.filter(item => isDraftEmailValid(item.email));
  const allValidSelected = validItems.length > 0 && validItems.every(item => item.selected);
  const selectedValidCount = validItems.filter(item => item.selected).length;

  const handleFileInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = e.target.files ? Array.from(e.target.files) : [];
    e.target.value = '';
    if (selected.length > 0) {
      onUploadFiles(selected);
    }
  };

  const handleDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.stopPropagation();
    setDragging(false);
    if (disabled || recognizing || isFull) return;
    const dropped = Array.from(e.dataTransfer.files ?? []);
    if (dropped.length > 0) {
      onUploadFiles(dropped);
    }
  };

  return (
    <div className="space-y-4">
      {/* 拖拽 / 点击 / 粘贴截图上传区 */}
      <div
        data-testid="invite-image-dropzone"
        onDragOver={e => {
          e.preventDefault();
          if (!disabled && !recognizing && !isFull) setDragging(true);
        }}
        onDragLeave={e => {
          e.preventDefault();
          setDragging(false);
        }}
        onDrop={handleDrop}
        onClick={() => {
          if (!disabled && !recognizing && !isFull) {
            fileInputRef.current?.click();
          }
        }}
        className={`relative cursor-pointer rounded-2xl border-2 border-dashed p-5 text-center transition-all ${
          disabled || recognizing || isFull
            ? 'cursor-not-allowed border-slate-200 bg-slate-50/70 opacity-75 dark:border-slate-700 dark:bg-slate-900/40'
            : dragging
              ? 'border-primary-500 bg-primary-50/60 dark:border-primary-400 dark:bg-primary-900/20'
              : 'border-slate-300 bg-slate-50/50 hover:border-primary-400 hover:bg-primary-50/30 dark:border-slate-700 dark:bg-slate-900/40 dark:hover:border-primary-500/60'
        }`}
      >
        <input
          ref={fileInputRef}
          data-testid="invite-image-input"
          type="file"
          multiple
          accept="image/png,image/jpeg,image/webp,image/gif,image/bmp"
          disabled={disabled || recognizing || isFull}
          onChange={handleFileInputChange}
          className="hidden"
        />

        {recognizing ? (
          <div className="flex flex-col items-center gap-2 py-2" data-testid="invite-image-recognizing">
            <div className="flex h-10 w-10 items-center justify-center rounded-full bg-primary-100 text-primary-600 dark:bg-primary-900/40 dark:text-primary-300">
              <Loader2 className="h-5 w-5 animate-spin" />
            </div>
            <div className="text-sm font-medium text-slate-800 dark:text-slate-100">
              {recognizeProgress || 'AI 正在自动识别简历图片中的姓名、邮箱地址与所在地…'}
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              识别完成后您可直接在下方表格核对、修改或补填邮箱后再发送邀请
            </p>
          </div>
        ) : (
          <div className="flex flex-col items-center gap-1.5">
            <div className="flex h-10 w-10 items-center justify-center rounded-full bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-300">
              {items.length > 0 ? <ImagePlus className="h-5 w-5" /> : <Upload className="h-5 w-5" />}
            </div>
            <div className="flex flex-wrap items-center justify-center gap-1.5 text-sm font-medium text-slate-800 dark:text-slate-100">
              <Sparkles className="h-4 w-4 text-primary-500" />
              {isFull
                ? `已达单批上限（${maxImages} 张），请先发送或移除部分记录`
                : items.length > 0
                  ? `点击或拖拽继续追加简历图片（还可添加 ${remainingSlots} 张，支持 Ctrl+V 粘贴截图）`
                  : '点击选择或拖拽多张简历图片到此处，也可直接 Ctrl+V 粘贴简历截图'}
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              支持 PNG / JPG / WEBP / GIF / BMP，单次最多 {maxImages} 张、单张 ≤ 10MB；上传后自动识别候选人姓名、邮箱地址与所在地
            </p>
          </div>
        )}
      </div>

      {/* 识别结果核对与编辑表格 */}
      {items.length > 0 && (
        <div
          data-testid="recognized-candidates-table"
          className="overflow-hidden rounded-2xl border border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900/60"
        >
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 bg-slate-50/80 px-4 py-2.5 text-xs dark:border-slate-700 dark:bg-slate-800/70">
            <div className="flex flex-wrap items-center gap-3 text-slate-600 dark:text-slate-300">
              <label className="inline-flex cursor-pointer items-center gap-1.5 font-medium">
                <input
                  type="checkbox"
                  checked={allValidSelected}
                  disabled={validItems.length === 0 || disabled}
                  onChange={e => onToggleSelectAllValid(e.target.checked)}
                  className="h-4 w-4 accent-primary-500"
                />
                全选有效邮箱
              </label>
              <span>
                共识别 <strong>{items.length}</strong> 张图片 · 已勾选{' '}
                <strong className="text-primary-600 dark:text-primary-400">{selectedValidCount}</strong> 位有效候选人
              </span>
            </div>
            <button
              type="button"
              disabled={disabled || recognizing}
              onClick={onClearAll}
              className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-rose-600 hover:bg-rose-50 disabled:opacity-50 dark:text-rose-400 dark:hover:bg-rose-900/20"
            >
              <Trash2 className="h-3.5 w-3.5" />
              清空全部
            </button>
          </div>

          <div className="max-h-72 overflow-x-auto overflow-y-auto">
            <table className="w-full text-left text-xs">
              <thead className="sticky top-0 z-10 border-b border-slate-200 bg-slate-50 text-[11px] font-semibold text-slate-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-400">
                <tr>
                  <th className="w-9 px-3 py-2.5 text-center">选择</th>
                  <th className="px-3 py-2.5">简历图片</th>
                  <th className="min-w-[120px] px-3 py-2.5">候选人姓名</th>
                  <th className="min-w-[210px] px-3 py-2.5">邮箱地址 *（用于发信）</th>
                  <th className="min-w-[130px] px-3 py-2.5">识别地址 / 电话</th>
                  <th className="px-3 py-2.5">状态</th>
                  <th className="w-10 px-3 py-2.5 text-right">移除</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                {items.map((item, idx) => {
                  const normalizedEmail = normalizeDraftEmail(item.email);
                  const emailValid = isDraftEmailValid(item.email);
                  const isDuplicate = emailValid && (emailCounts[normalizedEmail] ?? 0) > 1;

                  return (
                    <tr
                      key={item.id}
                      data-testid={`recognized-row-${idx}`}
                      className={`transition-colors ${
                        item.selected && emailValid
                          ? 'bg-primary-50/20 dark:bg-primary-900/10'
                          : 'hover:bg-slate-50/60 dark:hover:bg-slate-800/40'
                      }`}
                    >
                      <td className="px-3 py-2.5 text-center align-middle">
                        <input
                          type="checkbox"
                          data-testid={`recognized-check-${idx}`}
                          checked={item.selected && emailValid}
                          disabled={!emailValid || disabled}
                          title={emailValid ? '勾选后发送面试邀请' : '请先填写合法的候选人邮箱'}
                          onChange={e => onUpdateItem(item.id, { selected: e.target.checked })}
                          className="h-4 w-4 accent-primary-500 disabled:opacity-40"
                        />
                      </td>

                      <td className="px-3 py-2.5 align-middle">
                        <div className="flex items-center gap-2">
                          {item.previewUrl ? (
                            <button
                              type="button"
                              onClick={() =>
                                setPreviewTarget({ url: item.previewUrl!, filename: item.filename })
                              }
                              title="点击放大查看原简历图片"
                              className="group relative h-10 w-10 shrink-0 overflow-hidden rounded-lg border border-slate-200 bg-slate-100 dark:border-slate-700 dark:bg-slate-800"
                            >
                              <img
                                src={item.previewUrl}
                                alt={item.filename}
                                className="h-full w-full object-cover"
                              />
                              <span className="absolute inset-0 hidden items-center justify-center bg-black/45 text-white group-hover:flex">
                                <ZoomIn className="h-3.5 w-3.5" />
                              </span>
                            </button>
                          ) : (
                            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-slate-100 text-[10px] text-slate-400 dark:border-slate-700 dark:bg-slate-800">
                              无预览
                            </div>
                          )}
                          <div className="max-w-[110px] truncate text-slate-700 dark:text-slate-200" title={item.filename}>
                            {item.filename}
                          </div>
                        </div>
                      </td>

                      <td className="px-3 py-2.5 align-middle">
                        <input
                          data-testid={`recognized-name-${idx}`}
                          maxLength={64}
                          disabled={disabled}
                          value={item.candidateName}
                          onChange={e => onUpdateItem(item.id, { candidateName: e.target.value })}
                          placeholder="候选人姓名"
                          className="w-full rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                        />
                      </td>

                      <td className="px-3 py-2.5 align-middle">
                        <input
                          data-testid={`recognized-email-${idx}`}
                          maxLength={255}
                          disabled={disabled}
                          value={item.email}
                          onChange={e => {
                            const nextEmail = e.target.value;
                            const nextValid = isDraftEmailValid(nextEmail);
                            onUpdateItem(item.id, {
                              email: nextEmail,
                              selected: nextValid ? (emailValid ? item.selected : true) : false,
                            });
                          }}
                          placeholder="请补填候选人邮箱地址"
                          className={`w-full rounded-lg border px-2.5 py-1.5 text-xs focus:outline-none ${
                            emailValid
                              ? 'border-slate-200 bg-white text-slate-900 focus:border-primary-500 dark:border-slate-700 dark:bg-slate-900 dark:text-white'
                              : 'border-amber-400 bg-amber-50/50 text-slate-900 focus:border-amber-500 dark:border-amber-600 dark:bg-amber-900/20 dark:text-white'
                          }`}
                        />
                        {isDuplicate && (
                          <div className="mt-0.5 text-[10px] text-amber-600 dark:text-amber-400">
                            重复邮箱发送时自动合并
                          </div>
                        )}
                      </td>

                      <td
                        className="px-3 py-2.5 align-middle text-slate-600 dark:text-slate-300"
                        data-testid={`recognized-location-${idx}`}
                      >
                        {item.location ? (
                          <div className="flex items-center gap-1 truncate" title={item.location}>
                            <MapPin className="h-3 w-3 shrink-0 text-slate-400" />
                            <span className="max-w-[120px] truncate">{item.location}</span>
                          </div>
                        ) : (
                          <span className="text-slate-400">—</span>
                        )}
                        {item.phone && (
                          <div className="mt-0.5 flex items-center gap-1 text-[11px] text-slate-400">
                            <Phone className="h-2.5 w-2.5 shrink-0" />
                            <span>{item.phone}</span>
                          </div>
                        )}
                      </td>

                      <td className="px-3 py-2.5 align-middle">
                        {emailValid ? (
                          <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 text-[11px] font-medium text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300">
                            <CheckCircle2 className="h-3 w-3" />
                            已就绪
                          </span>
                        ) : (
                          <span
                            className="inline-flex items-center gap-1 rounded-full bg-amber-50 px-2 py-0.5 text-[11px] font-medium text-amber-700 dark:bg-amber-900/30 dark:text-amber-300"
                            title={item.serverError || '请手动填写有效邮箱地址'}
                          >
                            <AlertCircle className="h-3 w-3 shrink-0" />
                            <span className="max-w-[130px] truncate">
                              {item.email.trim()
                                ? '邮箱格式有误'
                                : item.serverError || '待补填邮箱'}
                            </span>
                          </span>
                        )}
                      </td>

                      <td className="px-3 py-2.5 text-right align-middle">
                        <button
                          type="button"
                          data-testid={`recognized-remove-${idx}`}
                          disabled={disabled}
                          onClick={() => onRemoveItem(item.id)}
                          className="rounded-lg p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600 disabled:opacity-40 dark:hover:bg-rose-900/20 dark:hover:text-rose-400"
                          title="移除该条记录"
                        >
                          <X className="h-4 w-4" />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* 原图放大预览浮层 */}
      {previewTarget && (
        <div
          onClick={() => setPreviewTarget(null)}
          className="fixed inset-0 z-[60] flex items-center justify-center bg-black/75 p-4"
        >
          <div
            onClick={e => e.stopPropagation()}
            className="relative max-h-[88vh] max-w-3xl overflow-auto rounded-2xl bg-white p-3 shadow-2xl dark:bg-slate-900"
          >
            <div className="mb-2 flex items-center justify-between gap-4 px-1">
              <span className="truncate text-xs font-medium text-slate-700 dark:text-slate-200">
                {previewTarget.filename}
              </span>
              <button
                type="button"
                onClick={() => setPreviewTarget(null)}
                className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-800"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <img
              src={previewTarget.url}
              alt={previewTarget.filename}
              className="max-h-[78vh] w-auto rounded-xl object-contain"
            />
          </div>
        </div>
      )}
    </div>
  );
}
