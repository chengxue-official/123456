import io
import re

t = io.open('dex3.txt', encoding='utf-8', errors='replace').read()

start = t.find("'Lcom/houg/tv/SoftwareRenderersFactory;")
print('descriptor 位置:', start)
if start >= 0:
    cs = t.rfind('Class #', 0, start)
    ce = t.find('Class #', cs + 100)
    seg = t[cs:ce if ce > 0 else cs + 120000]
    print('段落长度:', len(seg))
    methods = re.findall(r"name\s*:\s*'([^']+)'", seg)
    print('方法/字段名:', sorted(set(methods)))
    for kw in ['c2.android.', 'omx.google.', 'google', 'sw.', 'MediaCodecSelector']:
        print(repr(kw), '出现次数:', seg.count(kw))
