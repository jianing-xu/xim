#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mvn clean verify
(cd frontend && npm ci && npm test && npm run build)
mvn -f backend/pom.xml dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/release/lib
mkdir -p backend/target/release
cp backend/target/xim-1.0-SNAPSHOT.jar backend/target/release/xim.jar
printf '%s\n' 'Backend bundle: backend/target/release (xim.jar + lib/); frontend: frontend/dist'
