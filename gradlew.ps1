$ErrorActionPreference = "Stop"
$wrapperJar = Join-Path $PSScriptRoot "gradle/wrapper/gradle-wrapper.jar"
& java -classpath $wrapperJar org.gradle.wrapper.GradleWrapperMain @args
exit $LASTEXITCODE
