#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mvn compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
exec java -cp "target/classes:$(cat target/classpath.txt)" cn.xjn.xim.auth.AccountAdmin "$@"
