#!/usr/bin/env bash
# Build + run WITHOUT Maven (uses the jars in ./lib). Needs JDK 17+.
#   ./run.sh            → web dashboard on http://localhost:8080
#   ./run.sh --cli      → console report + exports in ./out
#   ./run.sh test       → run the JUnit tests
set -e
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes build/test-classes
javac --release 17 -encoding UTF-8 -d build/classes -cp "lib/*" $(find src/main/java -name "*.java")
cp -r src/main/resources/* build/classes/
if [ "$1" = "test" ]; then
  javac --release 17 -encoding UTF-8 -d build/test-classes -cp "build/classes:lib/*" $(find src/test/java -name "*.java")
  TESTS=$(cd src/test/java && find . -name "*Test.java" | sed 's#^\./##; s#\.java$##; s#/#.#g')
  java -cp "build/test-classes:build/classes:lib/*" org.junit.runner.JUnitCore $TESTS
else
  java -Dstdout.encoding=UTF-8 -cp "build/classes:lib/*" com.balancer.App "$@"
fi
