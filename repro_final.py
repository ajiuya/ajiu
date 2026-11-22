"""验证：两个不同的轮都从持久化历史里继承同一个会话级折叠头 key（修正去重检查）。"""

prev = {
    "a1": "turn:head:session:s1", "t1": "turn:head:session:s1",
    "a2": "turn:head:session:s1", "t2": "turn:head:session:s1",
}
messages = [
    ("u0", 'USER', False), ("a1", 'ASSISTANT', False), ("t1", 'TOOL', False),
    ("u1", 'USER', False), ("a2", 'ASSISTANT', False), ("t2", 'TOOL', False),
]


def split(msgs):
    leading, turns = [], []
    user, body = None, None
    for m in msgs:
        if m[1] == 'USER' and not m[2]:
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


leading, turns = split(messages)
keys = {}
if leading:
    k = next((prev[m[0]] for m in reversed(leading) if m[0] in prev), None)
    for m in leading:
        keys[m[0]] = k
for u, body in turns:
    k = prev.get(u[0]) or next((prev[m[0]] for m in body if m[0] in prev), None) or f"turn:{u[0]}"
    keys[u[0]] = k
    for m in body:
        keys[m[0]] = k

item_keys = []
print("LazyColumn item keys:")
for u, body in turns:
    stable = keys.get(u[0]) or f"turn:{u[0]}"
    header = stable + "#header"
    item_keys.append(header)
    print(f"  轮 {u[0]}: header={header}")

print("\n所有 header item key:", item_keys)
dups = [k for k in set(item_keys) if item_keys.count(k) > 1]
print("重复 key:", dups)
print("结论:", "复现崩溃 IllegalArgumentException" if dups else "未复现")
