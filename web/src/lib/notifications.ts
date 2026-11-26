// Web 端系统通知与标签页标题闪烁管理器

const FLASH_INTERVAL_MS = 1100;

let flashTimerId: number | null = null;
let flashBaseTitle = '湾聊 · BayChat';
let flashAlertTitle = '收到新消息 · BayChat';
let flashTick = false;

export function isNotificationSupported(): boolean {
  return typeof window !== 'undefined' && typeof Notification !== 'undefined';
}

export function getNotificationPermission(): NotificationPermission | 'unsupported' {
  if (!isNotificationSupported()) return 'unsupported';
  return Notification.permission;
}

export async function requestNotificationPermission(): Promise<NotificationPermission | 'unsupported'> {
  if (!isNotificationSupported()) return 'unsupported';
  if (Notification.permission !== 'default') {
    return Notification.permission;
  }
  try {
    return await Notification.requestPermission();
  } catch {
    return Notification.permission;
  }
}

// 弹出浏览器原生桌面通知，点击后聚焦窗口并触发恢复房间回调
export function showWebNotification(params: {
  title: string;
  body: string;
  tag?: string;
  renotify?: boolean;
  onClick?: () => void;
}): void {
  if (!isNotificationSupported() || Notification.permission !== 'granted') return;
  try {
    const notice = new Notification(params.title, {
      body: params.body,
      tag: params.tag || 'baychat-msg',
      icon: '/favicon.svg',
      silent: false,
    });
    notice.onclick = () => {
      try {
        window.focus();
      } catch {
        // 忽略窗口聚焦限制
      }
      notice.close();
      params.onClick?.();
    };
    window.setTimeout(() => {
      try {
        notice.close();
      } catch {
        // 忽略关闭异常
      }
    }, 8000);
  } catch {
    // 部分移动浏览器仅支持 ServiceWorker 通知，静默降级
  }
}

// 启动后台标签页未读闪烁提醒（交替显示未读摘要与基础标题）
export function startTitleFlash(baseTitle: string, alertTitle: string): void {
  if (typeof document === 'undefined') return;
  flashBaseTitle = baseTitle;
  flashAlertTitle = alertTitle;
  document.title = alertTitle;
  if (flashTimerId !== null) return;
  flashTick = true;
  flashTimerId = window.setInterval(() => {
    flashTick = !flashTick;
    document.title = flashTick ? flashAlertTitle : flashBaseTitle;
  }, FLASH_INTERVAL_MS);
}

// 停止标题闪烁并恢复干净标题
export function stopTitleFlash(restoreTitle = '湾聊 · BayChat'): void {
  if (flashTimerId !== null) {
    clearInterval(flashTimerId);
    flashTimerId = null;
  }
  flashTick = false;
  if (typeof document !== 'undefined') {
    document.title = restoreTitle;
  }
}
