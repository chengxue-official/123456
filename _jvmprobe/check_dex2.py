import io
import re

t = io.open('dex3.txt', encoding='utf-8', errors='replace').read()

header = "Class descriptor  : 'Lcom/houg/tv/SoftwareRenderersFactory$SoftwareMediaCodecSelector;'"
i = t.find(header)
print('类头位置:', i)
if i >= 0:
    nxt = t.find('Class descriptor  :', i + len(header))
    seg = t[i:nxt if nxt > 0 else i + 20000]
    print('段落长度:', len(seg))
    methods = re.findall(r"name\s*:\s*'([^']+)'", seg)
    print('name 字段:', sorted(set(methods)))
    print()
    for kw in ['startsWith', 'contains', 'lowercase', 'isEmpty', 'isNotEmpty',
               'c2.android.', 'omx.google.', 'sw.', 'DEFAULT', 'getDecoderInfos',
               'MediaCodecSelector', 'filter']:
        print('%-22s %d' % (kw, seg.count(kw)))
    print()
    print('---- 段落节选（含字符串常量的部分）----')
    for kw in ['c2.android.', 'omx.google.', 'sw.']:
        p = seg.find(kw)
        if p > 0:
            print('...', seg[max(0, p - 260):p + 120].replace('\n', ' | '))
            print()
