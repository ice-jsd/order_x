import { useClipboard } from '@vueuse/core';

const { copy, isSupported } = useClipboard();

export async function handleCopy(source?: string) {
  if (!isSupported) {
    window.$message?.error('您的浏览器不支持 Clipboard API');
    return;
  }

  if (!source) {
    return;
  }

  if (navigator.clipboard && window.isSecureContext) {
    await copy(source);
  } else {
    const copyTarget = document.createElement('textarea');
    copyTarget.value = source;
    copyTarget.setAttribute('readonly', 'readonly');
    copyTarget.style.position = 'fixed';
    copyTarget.style.top = '-9999px';
    copyTarget.style.left = '-9999px';
    document.body.appendChild(copyTarget);
    const range = document.createRange();
    range.selectNode(copyTarget);
    const selection = window.getSelection();
    if (selection?.rangeCount) selection.removeAllRanges();
    selection?.addRange(range);
    copyTarget.select();
    document.execCommand('copy');
    document.body.removeChild(copyTarget);
  }
  window.$message?.success('复制成功');
}
