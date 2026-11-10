@echo off
chcp 65001 >nul
cd /d "%~dp0"

if not exist "out\WeightTrackerServer.class" (
    echo 尚未编译，正在编译...
    if exist "%JAVA_HOME%\bin\javac.exe" (
        "%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -d out src\JsonUtil.java src\WeightTrackerServer.java
    ) else (
        javac -encoding UTF-8 -d out src\JsonUtil.java src\WeightTrackerServer.java
    )
)

echo 启动减肥打卡服务: http://localhost:3000
if exist "%JAVA_HOME%\bin\java.exe" (
    "%JAVA_HOME%\bin\java.exe" -cp out WeightTrackerServer
) else (
    java -cp out WeightTrackerServer
)

pause
