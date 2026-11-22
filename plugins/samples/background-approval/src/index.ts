import {
  definePlugin, hostCall, type JsonValue, type PluginArgs, type PluginHandler,
  type ObserveHookResult
} from '@jieapi/plugin-sdk';

interface PendingNotice {
  requestId: string;
  sessionId: string;
  toolName: string;
  preview: string;
  agentName: string | null;
}

let ballId: string | null = null;
let ballPromise: Promise<string> | null = null;
let notice: PendingNotice | null = null;

async function ensureBall(): Promise<string> {
  if (ballId) return ballId;
  ballPromise ??= hostCall('host.overlay.create', {
    surface: 'ball', width: 48, height: 48, visibilityPolicy: 'background'
  }).then((info) => {
    ballId = info.overlayId;
    return ballId;
  }).finally(() => { ballPromise = null; });
  return ballPromise;
}

async function publish(): Promise<void> {
  await ensureBall();
  await hostCall('host.overlay.postMessage', {
    surface: 'ball', payload: { type: 'approvalState', request: notice, count: notice ? 1 : 0 }
  });
}

const handlers = {
  'hooks.boot': async () => {
    await publish();
    return { ok: true, overlayId: ballId };
  },

  'hooks.observePermission': async (args: PluginArgs): Promise<ObserveHookResult> => {
    const sessionId = typeof args.sessionId === 'string' ? args.sessionId : '';
    const requestId = typeof args.callId === 'string' ? args.callId : '';
    if (!sessionId || !requestId) return { logs: ['缺少会话标识，请回到应用内查看待确认项。'] };
    notice = {
      sessionId, requestId,
      toolName: typeof args.toolName === 'string' ? args.toolName : '工具',
      preview: '此浮层仅提示待确认状态。请返回阿九工作台，由本人在原生界面确认。',
      agentName: typeof args.agentName === 'string' ? args.agentName : null
    };
    try {
      await publish();
    } catch {
      return { logs: ['提示浮层不可用；原生授权流程不受影响。'] };
    }
    return { logs: ['已显示只读提示；授权仍由原生界面处理。'] };
  },

  'hooks.runFinished': async (args: PluginArgs): Promise<ObserveHookResult> => {
    if (notice && args.sessionId === notice.sessionId) {
      notice = null;
      if (ballId) await publish();
    }
    return { logs: [] };
  },

  'overlay.message': async (args: PluginArgs) => {
    if (ballId && args.overlayId && args.overlayId !== ballId) return { ok: false };
    const payload = args.payload && typeof args.payload === 'object'
      ? args.payload as Record<string, JsonValue> : {};
    if (payload.type === 'openApp' && notice) {
      if (payload.requestId !== notice.requestId) return { ok: false };
      await hostCall('host.chat.openSession', { sessionId: notice.sessionId });
      return { ok: true };
    }
    if (payload.type === 'ballReady' || payload.type === 'ballClick') await publish();
    return { ok: true };
  }
} satisfies Record<string, PluginHandler>;

definePlugin({ handlers });
