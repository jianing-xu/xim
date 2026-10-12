#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mvn compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
# Pass JVM options as separate script arguments, e.g. ./run.sh -Dxim.web.port=9090.
exec java "$@" -cp "target/classes:$(cat target/classpath.txt)" cn.xjn.xim.server.NettyServer
