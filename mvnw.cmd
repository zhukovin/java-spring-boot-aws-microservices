@REM Apache Maven Wrapper startup script (Windows)
@IF "%__MVNW_ARG0_NAME__%"=="" (SET "BASE_DIR=%~dp0") ELSE (SET "BASE_DIR=%__MVNW_ARG0_NAME__%")
@SET MAVEN_PROJECTBASEDIR=%BASE_DIR%
@SET MAVEN_WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar
@SET MAVEN_WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties
@FOR /F "usebackq tokens=1,2 delims==" %%A IN ("%MAVEN_WRAPPER_PROPERTIES%") DO @IF "%%A"=="wrapperUrl" SET WRAPPER_URL=%%B
@IF NOT EXIST "%MAVEN_WRAPPER_JAR%" (
  @powershell -Command "Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%MAVEN_WRAPPER_JAR%'"
)
@java -classpath "%MAVEN_WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%" %*
