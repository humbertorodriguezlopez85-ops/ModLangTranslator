$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$gsonJar = Join-Path $root "lib/gson-2.11.0.jar"
$outDir = Join-Path $root "out"
$srcFile = Join-Path $root "src/main/java/com/modtranslator/ModLangTranslatorApp.java"

New-Item -ItemType Directory -Force -Path (Join-Path $root "lib") | Out-Null
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

if (-not (Test-Path $gsonJar)) {
    Write-Host "Downloading Gson 2.11.0..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar" -OutFile $gsonJar
}

Write-Host "Compiling..."
javac -encoding UTF-8 -cp $gsonJar -d $outDir $srcFile
if ($LASTEXITCODE -ne 0) {
    throw "Compilation failed"
}

Write-Host "Starting app..."
java -cp "$outDir;$gsonJar" com.modtranslator.ModLangTranslatorApp
