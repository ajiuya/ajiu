"""精确复刻 buildChatItems 的完整 item 产出，重点检查 #header 与消息项 key 的冲突。

关键点：reconcileTurnKeys 把 leading 段所有消息映射到同一个 key；
buildChatItems 里 leading 折叠头 key = "<headKey>#header"，而消息项 key = message.id。
只有当某条消息的 id 恰好等于 "<headKey>#header" 时才会撞车——排除。

真正可疑的是：turnKeys 里不同消息被映射到同一个 key 后，
buildChatItems 的 turn 循环用 turnKeys[turn.userMessage.id] 作为 stableTurnKey。
若两个 turn 的 userMessage 拿到同一个 key，就会产生两个相同的 "#header"。
这需要 previousKeys 里存在跨 turn 复用。

下面验证：turnMessageKeys 在同一 session 内跨多次重建是否可能把同一 key 分配给两个不同 turn 的 user 消息。
"""
from collections import Counter


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

sid = "s1"
history = {}
scenarios = {
    "A: 直接两条 user": [U("u0"), A("a0"), U("u1"), A("a1")],
    "B: user 无过程": [U("u0"), U("u1"), A("a1")],
    "C: continuation": [U("u0"), A("a0"), U("u1")],
    "D: 连续后台通知": [U("u0"), A("a0"), ('n1', 'USER', True), ('n2', 'USER', True)],
}

for name, msgs in scenarios.items():
    h = {}
    keys = reconcile(msgs, h, sid)
    h.update(keys)
    led, turns = split(msgs)
    heads = []
    if led:
        head = h.get(led[-1][0]) or (f"turn:head:session:{sid}" if (not turns and sid) else f"turn:head:{led[-1][0]}")
        heads.append(head + "#header")
    for u, body in turns:
        stable = h.get(u[0]) or f"turn:{u[0]}"
        heads.append(stable + "#header")
    dup = [k for k, c in Counter(heads).items() if c > 1]
    print(f"{name}: heads={heads} dup={dup}")

print("\n--- 关键：注入一个已被污染的 prev（模拟旧版本/异常历史的持久化键）---")
polluted = {'u0': 'turn:head:session:s1', 'a0': 'turn:head:session:s1'}
msgs = [U("u0"), A("a0"), U("u1"), A("a1")]
keys = reconcile(msgs, polluted, sid)
print("reconciled:", keys)
led, turns = split(msgs)
heads = []
if led:
    head = keys.get(led[-1][0]) or f"turn:head:session:{sid}"
    heads.append(head + "#header")
for u, body in turns:
    stable = keys.get(u[0]) or f"turn:{u[0]}"
    heads.append(stable + "#header")
print("heads:", heads)
dup = [k for k, c in Counter(heads).items() if c > 1]
print("dup:", dup, "->", "复现崩溃" if dup else "未复现")
