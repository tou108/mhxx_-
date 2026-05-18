#!/bin/sh
# Gradle start up script for UN*X
GRADLE_OPTS="${GRADLE_OPTS:-""} -Xmx1536m"
APP_HOME=$(cd "$(dirname "$0")" && pwd)
exec "$APP_HOME/gradle/wrapper/gradle-wrapper" "$@"
# Fallback: use system gradle
if [ -z "$(which gradle)" ]; then
  echo "Gradle not found. Install from https://gradle.org"
  exit 1
fi
