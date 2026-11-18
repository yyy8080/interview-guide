import { useMemo } from 'react';

interface ProctorWatermarkProps {
  text: string;
}

function escapeXml(value: string): string {
  return value.replace(/[<>&'"]/g, ch =>
    ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;' })[ch] ?? ch,
  );
}

/**
 * 极淡的平铺文字水印（姓名 + 投递编号），抑制拍屏外传题目。
 * 不可选中、不拦截鼠标事件，对作答无影响。
 */
export default function ProctorWatermark({ text }: ProctorWatermarkProps) {
  const background = useMemo(() => {
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="320" height="200">`
      + `<text x="20" y="120" transform="rotate(-20 160 100)" fill="rgb(100,116,139)" fill-opacity="0.07" `
      + `font-size="15" font-family="sans-serif">${escapeXml(text)}</text></svg>`;
    return `url("data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}")`;
  }, [text]);

  return (
    <div
      aria-hidden="true"
      data-testid="proctor-watermark"
      className="pointer-events-none fixed inset-0 z-[5] select-none"
      style={{ backgroundImage: background, backgroundRepeat: 'repeat' }}
    />
  );
}
