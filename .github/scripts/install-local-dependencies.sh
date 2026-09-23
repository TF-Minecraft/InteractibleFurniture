#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after downloading the pinned JARs.
# Hash-qualified versions prevent different private JARs sharing a Maven cache key.
sha256sum --check .github/dependencies.sha256

mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/gson-2.14.0.jar" -DgroupId="local" -DartifactId="gson" \
    -Dversion="2.14.0-tfmc-2cbd119bf196" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/joml-1.10.9.jar" -DgroupId="local" -DartifactId="joml" \
    -Dversion="1.10.9-tfmc-feca4db85337" -Dpackaging=jar -DgeneratePom=true "$@"
