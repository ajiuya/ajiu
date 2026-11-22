import { definePlugin, emit, type PluginArgs, type PluginHandler, type ObserveHookResult } from '@jieapi/plugin-sdk';

const handlers = {
  'hooks.observePermission': (args: PluginArgs): ObserveHookResult => {
    emit('chat.permission.requested', {
      requestId: typeof args.callId === 'string' ? args.callId : '',
      sessionId: typeof args.sessionId === 'string' ? args.sessionId : null,
      toolName: typeof args.toolName === 'string' ? args.toolName : '工具',
      notice: '请回到宿主原生界面，由本人确认。此页面仅显示待确认状态。'
    });
    return { logs: ['授权仍由宿主原生界面处理。'] };
  },

  'hooks.runFinished': (args: PluginArgs): ObserveHookResult => {
    emit('chat.run.finished', {
      sessionId: typeof args.sessionId === 'string' ? args.sessionId : '',
      status: typeof args.status === 'string' ? args.status : 'finished'
    });
    return { logs: [] };
  },

  'actions.notifySession': (args: PluginArgs): { message: string } => ({
    message: args.sessionId
      ? `已触发会话动作 ${String(args.sessionId).slice(0, 8)}`
      : '未收到会话上下文'
  })
} satisfies Record<string, PluginHandler>;

definePlugin({ handlers });
