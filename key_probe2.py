"""在真实消息模型上枚举分裂路径，寻找两个 turn 共用同一 stableTurnKey 的可能。"""


def split(messages):
    leading, turns = [], []
    user, body = None, None
    for m in messages:
        mid, role, bg = m
        if role == 'USER' and not bg:
            if user:
                turns.append((user, body or []))
            user, body = m, []
        else:
            if body is None:
                leading.append(m)
            else:
                body.append(m)
    if user:
        turns.append((user, body or []))
    return leading, turns


def reconcile(messages, prev, sid):
    keys = {}
    leading, turns = split(messages)
    if leading:
        k = next((prev[m[0]] for m in reversed(leading) if m[0] in prev), None)
        if k is None:
            k = f"turn:head:session:{sid}" if (not turns and sid) else f"turn:head:{leading[-1][0]}"
        for m in leading:
            keys[m[0]] = k
    for u, body in turns:
        k = prev.get(u[0]) or next((prev[m[0]] for m in body if m[0] in prev), None) or f"turn:{u[0]}"
        keys[u[0]] = k
        for m in body:
            keys[m[0]] = k
    return keys


U = lambda i: (i, 'USER', False)
A = lambda i: (i, 'ASSISTANT', False)
T = lambda i: (i, 'TOOL', False)
N = lambda i: (i, 'USER', True)   # 后台通知（USER 角色但 isBackgroundNotification）

# 关键假设：后台通知在 splitChatTurns 中归入"当前轮的 body"。
# 但如果窗口从某个通知开始（前面没有 user），通知会进 leading。
# 这时 leading 非空，turns 可能也非空 -> headKey = leadingHeadKey(leading.last())。

cases = {
    "1. 通知开头 + 后续轮": [N("n1"), A("a0"), U("u0"), A("a1")],
    "2. 纯通知窗口": [N("n1"), A("a0"), N("n2"), A("a1")],
    "3. 通知在轮中": [U("u0"), N("n1"), A("a0"), U("u1"), A("a1")],
}

for name, msgs in cases.items():
    h = reconcile(msgs, {}, "s1")
    led, turns = split(msgs)
    heads = []
    if led:
        head = h.get(led[-1][0]) or (f"turn:head:session:s1" if (not turns) else f"turn:head:{led[-1][0]}")
        heads.append(("leading", head + "#header"))
    for u, body in turns:
        stable = h.get(u[0]) or f"turn:{u[0]}"
        heads.append(("turn:" + u[0], stable + "#header"))
    print(f"\n{name}")
    print("  leading ids:", [m[0] for m in led])
    print("  turns:", [(u[0], [b[0] for b in body]) for u, body in turns])
    print("  heads:", heads)
