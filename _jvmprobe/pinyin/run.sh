#!/usr/bin/env bash
# 在 JVM 上编译并验证 Pinyin（生产源码原文，不修改）
# 目的：验证 GB2312 区位码首字母映射，尤其是 X 段上界修复。
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE" || exit 1

export JAVA_HOME="${JAVA_HOME:-D:/dev/tools/jdk-21}"
JAVA="$JAVA_HOME/bin/java.exe"
JAVAC="$JAVA_HOME/bin/javac.exe"
if [ ! -f "$JAVAC" ]; then echo "找不到 javac：$JAVAC"; exit 1; fi

GC=/c/Users/Administrator/.gradle/caches/modules-2/files-2.1
pick() {
  find "$GC/$1" -name "$2" 2>/dev/null | sort -V | tail -1
}

KC=$(pick org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-*.jar")
STDLIB=$(pick org.jetbrains.kotlin/kotlin-stdlib "kotlin-stdlib-[0-9]*.jar")
CORO=$(pick org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm "kotlinx-coroutines-core-jvm-*.jar")
TROVE=$(pick org.jetbrains.intellij.deps/trove4j "trove4j-*.jar")
SCRIPT=$(pick org.jetbrains.kotlin/kotlin-script-runtime "kotlin-script-runtime-*.jar")
REFLECT=$(pick org.jetbrains.kotlin/kotlin-reflect "kotlin-reflect-*.jar")
ANNOT=$(pick org.jetbrains/annotations "annotations-*.jar")

if [ -z "$KC" ]; then echo "找不到 kotlin-compiler-embeddable"; exit 1; fi
if [ -z "$STDLIB" ]; then echo "找不到 kotlin-stdlib"; exit 1; fi

w() { cygpath -w "$1"; }

COMPILER_CP="$(w "$KC");$(w "$STDLIB");$(w "$CORO");$(w "$TROVE")"
if [ -n "$SCRIPT" ]; then COMPILER_CP="$COMPILER_CP;$(w "$SCRIPT")"; fi
if [ -n "$REFLECT" ]; then COMPILER_CP="$COMPILER_CP;$(w "$REFLECT")"; fi
if [ -n "$ANNOT" ]; then COMPILER_CP="$COMPILER_CP;$(w "$ANNOT")"; fi

rm -rf out
mkdir -p out/kotlin out/probe

echo "== 1/3 编译生产源码 Pinyin.kt（原文未改）=="
"$JAVA" -Dfile.encoding=UTF-8 -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 21 -nowarn \
  -d out/kotlin -classpath "$(w "$STDLIB")" \
  ../../app/src/main/java/com/houg/tv/util/Pinyin.kt 2>&1 | grep -av "^warning:"
if [ ! -f out/kotlin/com/houg/tv/util/Pinyin.class ]; then echo "Kotlin 编译失败"; exit 1; fi

echo "== 2/3 编译测试驱动 =="
"$JAVAC" -encoding UTF-8 -d out/probe \
  -classpath "$(w "$STDLIB");$(w "$HERE/out/kotlin")" \
  PinyinProbe.java || { echo "测试驱动编译失败"; exit 1; }

echo "== 3/3 运行首字母映射验证 =="
"$JAVA" -Dfile.encoding=UTF-8 \
  -classpath "$(w "$STDLIB");$(w "$HERE/out/kotlin");$(w "$HERE/out/probe")" \
  PinyinProbe
