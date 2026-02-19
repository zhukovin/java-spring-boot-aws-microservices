#!/bin/sh
# Apache Maven Wrapper startup script

if [ -n "$JAVA_HOME" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA=$(which java)
fi

if [ -z "$JAVA" ]; then
  echo "ERROR: JAVA_HOME is not set and java is not in PATH."
  exit 1
fi

MAVEN_PROJECTBASEDIR="$(cd "$(dirname "$0")" && pwd)"
MAVEN_WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"
MAVEN_WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"

if [ ! -e "$MAVEN_WRAPPER_JAR" ]; then
  WRAPPER_URL=$(grep "^wrapperUrl=" "$MAVEN_WRAPPER_PROPERTIES" | sed 's/.*=//;s/\r//')
  if [ -n "$WRAPPER_URL" ]; then
    echo "Downloading Maven Wrapper..."
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL -o "$MAVEN_WRAPPER_JAR" "$WRAPPER_URL"
    elif command -v wget >/dev/null 2>&1; then
      wget -q -O "$MAVEN_WRAPPER_JAR" "$WRAPPER_URL"
    fi
  fi
fi

exec "$JAVA" -classpath "$MAVEN_WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain \
  -Dmaven.multiModuleProjectDirectory="$MAVEN_PROJECTBASEDIR" \
  "$@"
