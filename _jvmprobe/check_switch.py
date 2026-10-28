import re
import zipfile


def switch_value(apk):
    z = zipfile.ZipFile(apk)
    d = z.read('lib/armeabi-v7a/libduanju_core.so')
    # Go -X 注入后，buildAllSources 的内容在只读数据段，形态是 NUL 结尾 C 字符串，
    # 但 Go 对短字符串用 struct {ptr, len} 引用，串本身不补 NUL。
    # 更可靠的判别：Flutter 侧的 --dart-define 会写进 libapp.so，但 Go 侧
    # 直接对比「真果鉴与红果鉴两个 .so 的差异」不可行（同为最新源码编译）。
    # 换权威判别：libapp.so 里的 ALL_SOURCES 常量由 Dart 编译期决定，
    # 而 Go 侧用链接器 -X 注入。两包 .so 大小完全相同、内容不同（哈希已不同），
    # 无法用字符串计数区分。改用间接但可靠的判别：
    # 真果鉴的 Dart 侧站源管理页含全部站源文案，红果鉴只有红果。
    # 查 libapp.so（Dart AOT）里的站源字符串。
    d2 = z.read('lib/armeabi-v7a/libapp.so')
    sources = ['yaguo', 'maoguo', 'fanguo', 'guanguo', 'heguo', 'xingguo',
               'huaguo', 'niuguo', 'piguo', 'wuguo']
    hits = sum(1 for s in sources if s.encode() in d2)
    return hits, len(sources)


for tag, p in [('红果鉴32位', 'dist/android/hongguojian-0.2.92+2098-armeabi-v7a.apk'),
               ('真果鉴32位', 'dist/android/zhenguojian-0.2.92+2098-armeabi-v7a.apk')]:
    hits, total = switch_value(p)
    print('%-9s Dart侧站源字符串 %d/%d %s' % (
        tag, hits, total,
        '（全站源）' if hits == total else '（仅红果档）'))
