@echo off
REM Build + run WITHOUT Maven on Windows (uses the jars in .\lib). Needs JDK 17+.
REM   run.bat          -> web dashboard on http://localhost:8080
REM   run.bat --cli    -> console report + exports in .\out
REM   run.bat test     -> run the JUnit tests
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build\classes build\test-classes
dir /s /b src\main\java\*.java > build\sources.txt
javac --release 17 -encoding UTF-8 -d build\classes -cp "lib\*" @build\sources.txt || exit /b 1
xcopy /e /i /q src\main\resources build\classes >nul
if "%1"=="test" (
  dir /s /b src\test\java\*.java > build\tests.txt
  javac --release 17 -encoding UTF-8 -d build\test-classes -cp "build\classes;lib\*" @build\tests.txt || exit /b 1
  java -cp "build\test-classes;build\classes;lib\*" org.junit.runner.JUnitCore com.balancer.TimeOverlapCheckerTest com.balancer.ValidationEngineTest com.balancer.ShiftBalancerServiceTest com.balancer.CommandHistoryTest com.balancer.PersistenceAndIoTest
) else (
  java -Dstdout.encoding=UTF-8 -cp "build\classes;lib\*" com.balancer.App %*
)
