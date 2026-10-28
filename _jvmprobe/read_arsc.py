import zipfile

z = zipfile.ZipFile(r'D:\hongguo\HougTime\app\build\outputs\apk\debug\app-debug.apk')
raw = z.read('resources.arsc')

# arsc 首块即全局字符串池（RES_STRING_POOL_TYPE=0x0001）。
# 用「utf8 长度前缀」格式扫描：1~2 字节字符数 + 1~2 字节字节数 + 字节数据 + \0
def read_len(b, i):
    v = b[i]
    if v & 0x80:
        return ((v & 0x7F) << 8) | b[i + 1], i + 2
    return v, i + 1

results = []
i = 0
n = len(raw)
while i < n - 4:
    # 尝试把 i 当作 utf8 串起始
    try:
        uchars, j = read_len(raw, i)
        if uchars == 0 or uchars > 200:
            i += 1
            continue
        bchars, k = read_len(raw, j)
        if bchars == 0 or bchars > 600 or k + bchars >= n:
            i += 1
            continue
        data = raw[k:k + bchars]
        text = data.decode('utf-8')
        if b'\x00' in raw[k:k + bchars]:
            i += 1
            continue
        if any('\u4e00' <= c <= '\u9fff' for c in text):
            results.append(text)
        i = k + bchars
    except Exception:
        i += 1

uniq = sorted(set(results))
print('含中文的字符串池条目:', len(uniq))
for s in uniq:
    if '解码' in s or '软解' in s or '硬解' in s or '切换' in s:
        print('  ', repr(s))
