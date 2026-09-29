@rem 精简版 gradlew.bat
@echo off
setlocal
set DIRNAME=%~dp0
if "%JAVA_HOME%"=="" (set JAVACMD=java) else (set JAVACMD="%JAVA_HOME%\bin\java.exe")
"%JAVACMD%" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal
