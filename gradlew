#!/bin/sh
# 精简版 gradlew：负责拉起 gradle/wrapper/gradle-wrapper.jar，
# 由它按 gradle-wrapper.properties 下载并使用指定版本的 Gradle。
set -e
APP_HOME=$(cd "$(dirname "$0")" && pwd)

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVACMD=java
else
  echo "错误：没有找到 Java。请安装 JDK 17 并设置 JAVA_HOME。" >&2
  exit 1
fi

exec "$JAVACMD" -Xmx64m -Xms64m \
  "-Dorg.gradle.appname=gradlew" \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
