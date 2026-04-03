import { watch } from 'vue';
import { useEventSource } from '@vueuse/core';
import { useNoticeStore } from '@/store/modules/notice';
import { $t } from '@/locales';
import { localStg } from './storage';

export const APP_SSE_MESSAGE_EVENT = 'app:sse-message';

function tryParseMessage(raw: string) {
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export const initSSE = (url: string) => {
  const token = localStg.get('token');
  if (import.meta.env.VITE_APP_SSE === 'N' || !token) {
    return;
  }

  const sseUrl = `${url}?Authorization=Bearer ${token}&clientid=${import.meta.env.VITE_APP_CLIENT_ID}`;
  const { data, error } = useEventSource(sseUrl, [], {
    autoReconnect: {
      retries: 5,
      delay: 5000,
      onFailed() {
        // eslint-disable-next-line no-console
        console.warn('Failed to connect to SSE after 5 attempts.');
      }
    }
  });

  watch(error, () => {
    if (!error.value || error.value?.isTrusted) {
      return;
    }
    // eslint-disable-next-line no-console
    console.error('SSE connection error:\n', error.value);
    error.value = null;
  });

  watch(data, () => {
    if (!data.value) return;

    const raw = data.value;
    const parsed = tryParseMessage(raw);

    if (parsed) {
      window.dispatchEvent(new CustomEvent(APP_SSE_MESSAGE_EVENT, { detail: parsed }));
    }

    if (parsed?.module === 'ticket_register' || parsed?.module === 'ticket_login') {
      if (parsed.stepStatus === 'completed') {
        useNoticeStore().addNotice({
          message: parsed.message,
          read: false,
          time: new Date().toLocaleString()
        });

        window.$notification?.create({
          title: parsed.module === 'ticket_login' ? '票务登录' : '票务注册',
          content: parsed.message,
          type: parsed.failedCount > 0 ? 'warning' : 'success',
          duration: 3000
        });
      }

      data.value = null;
      return;
    }

    useNoticeStore().addNotice({
      message: raw,
      read: false,
      time: new Date().toLocaleString()
    });

    let content = raw;
    const noticeType = content.match(/\[dict\.(.*?)\]/)?.[1];
    if (noticeType) {
      content = content.replace(`dict.${noticeType}`, $t(`dict.${noticeType}` as App.I18n.I18nKey));
    }

    window.$notification?.create({
      title: '消息',
      content,
      type: 'success',
      duration: 3000
    });
    data.value = null;
  });
};
