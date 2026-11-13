#!/bin/sh
set -eu
cd "$(dirname "$0")"
kotlinc src/main/kotlin/RemoteServer.kt ../shared/src/main/kotlin/com/pocketcoder/protocol/Mjpeg.kt -include-runtime -jvm-target 17 -d pocketcoder-server.jar
java -jar pocketcoder-server.jar --self-test
