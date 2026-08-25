import re

LOG_HEADER = re.compile(r"^(\d{2}:\d{2}:\d{2}\.\d{3})\s+\[(\w+)\s*\]\s+\[([^\]]*)\]\s+\[([^\]]*)\]\s+([\w.$]+)\s+-\s+(.*)$", re.S)
EVENT_PREFIX = re.compile(r"^\[([^\]]+)\]\[([A-Z_][A-Z0-9_]*)\]\[([^\]]*)\]\s*(.*)$", re.S)
EVENT_PREFIX_2 = re.compile(r"^\[([^\]]+)\]\[([A-Z_][A-Z0-9_]*)\]\s*(.*)$", re.S)

samples = []
with open('logs/dingring.log', encoding='utf-8') as f:
    for line in f:
        samples.append(line.rstrip('\n'))
        if len(samples) >= 2000:
            break

hdr_ok = hdr_fail = 0
ev3 = ev2 = ev_none = 0
codes = {}
traces = set()
for s in samples:
    m = LOG_HEADER.match(s)
    if not m:
        hdr_fail += 1
        if hdr_fail <= 3:
            print("HEADER_FAIL:", s[:100])
        continue
    hdr_ok += 1
    if m.group(3) and m.group(3) != '-':
        traces.add(m.group(3))
    body = m.group(6)
    m3 = EVENT_PREFIX.match(body)
    if m3:
        ev3 += 1
        codes[m3.group(2)] = codes.get(m3.group(2), 0) + 1
    else:
        m2 = EVENT_PREFIX_2.match(body)
        if m2:
            ev2 += 1
            codes[m2.group(2)] = codes.get(m2.group(2), 0) + 1
        else:
            ev_none += 1

print(f"header ok={hdr_ok} fail={hdr_fail}; ev3={ev3} ev2={ev2} none={ev_none}")
print("top codes:", dict(sorted(codes.items(), key=lambda x: -x[1])[:15]))
print("sample traces:", list(traces)[:5])
