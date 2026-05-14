$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$artifactId = 'mod-lang-translator'
$version = '1.0.0'
$appName = 'ModLangTranslator'
$mainJar = "$artifactId-$version-all.jar"
$mainClass = 'com.modtranslator.ModLangTranslatorApp'

# Ensure Google Cloud API key is set
$googleApiKey = $env:GOOGLE_API_KEY
if (-not $googleApiKey) {
    throw 'Environment variable GOOGLE_API_KEY is not set. Please set it before running this script.'
}

Write-Host 'Building fat jar with Maven...'
mvn -q -DskipTests clean package
if ($LASTEXITCODE -ne 0) {
    throw 'Maven build failed.'
}

$jarPath = Join-Path $root "target/$mainJar"
if (-not (Test-Path $jarPath)) {
    throw "Expected jar not found: $jarPath"
}

$dist = Join-Path $root 'dist'
$appImageDir = Join-Path $dist $appName

if (Test-Path $appImageDir) {
    Remove-Item -Recurse -Force $appImageDir
}

New-Item -ItemType Directory -Force -Path $dist | Out-Null

Write-Host 'Packaging Windows executable with jpackage...'
jpackage `
  --type app-image `
  --name $appName `
  --dest $dist `
  --input (Join-Path $root 'target') `
  --main-jar $mainJar `
  --main-class $mainClass `
  --java-options "-Dfile.encoding=UTF-8 -Dgoogle.api.key=$googleApiKey" `
  --win-console

if ($LASTEXITCODE -ne 0) {
    throw 'jpackage failed.'
}

$exePath = Join-Path $appImageDir "$appName.exe"
Write-Host "Done. Executable: $exePath"
