#Requires -Version 5.1
<#
.SYNOPSIS
    在 Windows 开发机上打包 Data Generator 部署 zip（含 Linux / Windows 启停脚本）

.DESCRIPTION
    生成 zip 包，解压后目录结构：
      bin/linux/     Linux 启停脚本（web / ai / all）
      bin/windows/   Windows 启停脚本
      lib/           dg-web 与 dg-ai 应用 jar
      conf/          dg-web 本地配置模板
      conf/dg-ai/    dg-ai 本地配置模板
      jdk/           可选内置 JDK（存在时启动脚本优先使用，不修改系统 JDK）

.PARAMETER JdkPath
    指定要打入包内的 JDK 目录（解压后为 ./jdk）

.PARAMETER OutputDir
    zip 输出目录，默认 build/dist

.PARAMETER SkipBuild
    跳过 Maven 构建，使用已有 target jar

.EXAMPLE
    .\scripts\package.ps1
    .\scripts\package.ps1 -JdkPath D:\jdk-21
    .\scripts\package.ps1 -SkipBuild
#>
[CmdletBinding()]
param(
    [string] $JdkPath = "",
    [string] $OutputDir = "",
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDir) {
    $OutputDir = Join-Path $ProjectRoot "build\dist"
}

function Get-ProjectVersion {
    $pom = Join-Path $ProjectRoot "pom.xml"
    $match = Select-String -Path $pom -Pattern '<version>([^<]+)</version>' | Select-Object -First 1
    if (-not $match) {
        throw 'Cannot read version from pom.xml'
    }
    return $match.Matches[0].Groups[1].Value
}

function Copy-Tree {
    param(
        [string] $Source,
        [string] $Destination
    )
    if (-not (Test-Path $Source)) {
        throw ('Path not found: ' + $Source)
    }
    Copy-Item -Path $Source -Destination $Destination -Recurse -Force
}

function Repair-LinuxShellScript {
    param([string] $Content)
    # Windows 下 CRLF 可能导致行末 r 丢失（resolve_jar -> resolve_ja）
    $Content = $Content -replace '(?m)^(\s*)local script_di\s*$', '${1}local script_dir'
    $Content = $Content -replace '(?m)^(\s*)local version_line majo\s*$', '${1}local version_line major'
    $Content = $Content -replace '(?m)^(\s*)resolve_ja\s*$', '${1}resolve_jar'
    return $Content
}

function Assert-EnvShIntegrity {
    param([string] $Content)
    if ($Content -match '(?m)^\s*resolve_ja\s*$') {
        throw 'env.sh still contains truncated resolve_ja; fix source scripts/linux/env.sh'
    }
    if ($Content -notmatch '(?m)^\s*resolve_jar\s*$') {
        throw 'env.sh missing resolve_jar call in init_app_env'
    }
}

function Copy-LinuxShellScripts {
    param(
        [string] $SourceDir,
        [string] $DestinationDir
    )
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    Get-ChildItem -Path $SourceDir -Filter *.sh | ForEach-Object {
        $content = [System.IO.File]::ReadAllText($_.FullName)
        $content = $content -replace "`r`n", "`n"
        if ($content.Contains([char]13)) {
            $content = $content.Replace([string][char]13, [string][char]10)
        }
        $content = Repair-LinuxShellScript -Content $content
        if ($_.Name -eq 'env.sh') {
            Assert-EnvShIntegrity -Content $content
        }
        $dest = Join-Path $DestinationDir $_.Name
        [System.IO.File]::WriteAllText($dest, $content, $utf8NoBom)
    }
}

function Resolve-BundledJdk {
    param([string] $ExplicitPath)

    if ($ExplicitPath) {
        $javaExe = Join-Path $ExplicitPath "bin\java.exe"
        if (-not (Test-Path $javaExe)) {
            throw ('Invalid JDK, missing: ' + $javaExe)
        }
        return (Resolve-Path $ExplicitPath).Path
    }

    $defaultJdk = Join-Path $ProjectRoot "jdk"
    $defaultJava = Join-Path $defaultJdk "bin\java.exe"
    if ((Test-Path $defaultJava)) {
        return (Resolve-Path $defaultJdk).Path
    }

    return $null
}

$Version = Get-ProjectVersion
$WebArtifact = "dg-web-$Version.jar"
$AiArtifact = "dg-ai-$Version.jar"
$WebJarPath = Join-Path $ProjectRoot "dg-web\target\$WebArtifact"
$AiJarPath = Join-Path $ProjectRoot "dg-ai\target\$AiArtifact"
$DistName = "data-generator-$Version"
$StagingDir = Join-Path $ProjectRoot "build\staging\$DistName"

Write-Host '==> Version:' $Version

if (-not $SkipBuild) {
    Write-Host '==> Maven build (dg-web + dg-ai) ...'
    Push-Location $ProjectRoot
    try {
        # -am：同时构建 dg-core / 插件等上游模块，避免 fat jar 内嵌过期的本地仓库 dg-core
        & mvn clean package -pl dg-web,dg-ai -am -DskipTests -q
        if ($LASTEXITCODE -ne 0) {
            throw ('Maven build failed, exit code ' + $LASTEXITCODE)
        }
    }
    finally {
        Pop-Location
    }
}

if (-not (Test-Path $WebJarPath)) {
    throw ('Jar not found: ' + $WebJarPath + [Environment]::NewLine + 'Run: mvn clean package -pl dg-web,dg-ai -am -DskipTests')
}
if (-not (Test-Path $AiJarPath)) {
    throw ('Jar not found: ' + $AiJarPath + [Environment]::NewLine + 'Run: mvn clean package -pl dg-web,dg-ai -am -DskipTests')
}

Write-Host '==> Prepare staging directory ...'
if (Test-Path $StagingDir) {
    Remove-Item -Path $StagingDir -Recurse -Force
}

$dirs = @(
    "bin\linux",
    "bin\windows",
    "lib",
    "conf",
    "conf\dg-ai",
    "logs",
    "run",
    "data\configs\jobs",
    "data\job-logs"
)
foreach ($dir in $dirs) {
    New-Item -ItemType Directory -Path (Join-Path $StagingDir $dir) -Force | Out-Null
}

Copy-Item -Path $WebJarPath -Destination (Join-Path $StagingDir "lib\") -Force
Copy-Item -Path $AiJarPath -Destination (Join-Path $StagingDir "lib\") -Force
Copy-LinuxShellScripts -SourceDir (Join-Path $ProjectRoot "scripts\linux") -DestinationDir (Join-Path $StagingDir "bin\linux\")
Copy-Tree -Source (Join-Path $ProjectRoot "scripts\windows\*") -Destination (Join-Path $StagingDir "bin\windows\")

$webLocalExample = Join-Path $ProjectRoot "dg-web\src\main\resources\application-local.yml.example"
if (Test-Path $webLocalExample) {
    Copy-Item -Path $webLocalExample -Destination (Join-Path $StagingDir "conf\") -Force
}

$aiLocalExample = Join-Path $ProjectRoot "dg-ai\src\main\resources\application-local.yml.example"
if (Test-Path $aiLocalExample) {
    Copy-Item -Path $aiLocalExample -Destination (Join-Path $StagingDir "conf\dg-ai\") -Force
}

@'
# Copy to java.opts and uncomment JVM options
# JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC"
'@ | Set-Content -Path (Join-Path $StagingDir "conf\java.opts.example") -Encoding UTF8

$resolvedJdk = Resolve-BundledJdk -ExplicitPath $JdkPath
if ($resolvedJdk) {
    Write-Host '==> Bundle JDK:' $resolvedJdk
    Copy-Tree -Source $resolvedJdk -Destination (Join-Path $StagingDir "jdk")
}
else {
    Write-Host '==> No bundled JDK (will use system Java at runtime)'
}

$readme = @(
    "Data Generator $Version"
    "================================"
    ""
    "bin/linux/     Linux scripts"
    "  start.sh / stop.sh / status.sh       dg-web (port 8080)"
    "  start-ai.sh / stop-ai.sh / status-ai.sh   dg-ai (port 8081)"
    "  start-all.sh / stop-all.sh           both services"
    "bin/windows/   Windows scripts (same roles as above)"
    "lib/           dg-web-*.jar + dg-ai-*.jar"
    "conf/          dg-web local config template"
    "conf/dg-ai/    dg-ai local config template (LLM api-key)"
    "conf/java.opts optional JVM options"
    "jdk/           optional bundled JDK (preferred, does not change system JDK)"
    "data/          runtime data"
    "logs/          console logs (console.log / ai-console.log)"
    ""
    "Linux:"
    "  unzip $DistName.zip"
    "  cd $DistName"
    "  chmod +x bin/linux/*.sh"
    "  ./bin/linux/start-all.sh"
    ""
    "Windows:"
    "  extract $DistName.zip"
    "  cd $DistName"
    "  bin\windows\start-all.bat"
    ""
    "Requires JDK 21+. Web default port 8080, AI default port 8081."
    "Enable AI in Web console: set data-generator.ai.enabled=true in conf/application-local.yml"
    "and point remote-base-url to dg-ai (default http://localhost:8081)."
    "Set matching service-auth token in conf/application-local.yml (Web) and"
    "conf/dg-ai/application-local.yml (ai.remote-services.data-generator-web.service-auth-token)."
)
$readme | Set-Content -Path (Join-Path $StagingDir "README.txt") -Encoding UTF8

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$ZipPath = Join-Path $OutputDir "$DistName.zip"
if (Test-Path $ZipPath) {
    Remove-Item -Path $ZipPath -Force
}

Write-Host '==> Create zip:' $ZipPath
Compress-Archive -Path $StagingDir -DestinationPath $ZipPath -CompressionLevel Optimal

Write-Host ''
Write-Host 'Done:'
Write-Host "  $ZipPath"
Write-Host ''
Write-Host 'Linux: unzip, cd, ./bin/linux/start-all.sh (or start.sh / start-ai.sh)'
Write-Host 'Windows: extract, then run bin\windows\start-all.bat (or start.bat / start-ai.bat)'
