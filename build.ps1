param([string]$JdkPath = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

if ([string]::IsNullOrWhiteSpace($JdkPath)) {
    $compiler = Get-Command javac.exe -ErrorAction Stop
    $JdkPath = Split-Path (Split-Path $compiler.Source -Parent) -Parent
}
$java = Join-Path $JdkPath 'bin/java.exe'
$javac = Join-Path $JdkPath 'bin/javac.exe'
$jar = Join-Path $JdkPath 'bin/jar.exe'
$keytool = Join-Path $JdkPath 'bin/keytool.exe'
if (!(Test-Path -LiteralPath $java)) { throw "JDK not found: $JdkPath" }

New-Item -ItemType Directory -Force -Path tools,build/classes,build/test-classes,build/dex,dist | Out-Null
$downloads = [ordered]@{
    'api-102.0.0.aar' = 'https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar'
    'platform-35_r02.zip' = 'https://dl.google.com/android/repository/platform-35_r02.zip'
    'aapt2-windows.jar' = 'https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.6.1-11315950/aapt2-8.6.1-11315950-windows.jar'
    'r8-8.6.27.jar' = 'https://storage.googleapis.com/r8-releases/raw/8.6.27/r8.jar'
    'apksig-8.6.1.jar' = 'https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.6.1/apksig-8.6.1.jar'
    'json-20240303.jar' = 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar'
}
foreach ($item in $downloads.GetEnumerator()) {
    $destination = Join-Path 'tools' $item.Key
    if (!(Test-Path -LiteralPath $destination)) {
        Invoke-Checked 'curl.exe' @('-L','--fail','--show-error','--max-time','120','-o',$destination,$item.Value)
    }
}
Invoke-Checked 'python' @('tools/package_apk.py','prepare')
$compilePath = 'tools/android-35.jar;tools/libxposed-api-102.jar'
$sources = @(Get-ChildItem -LiteralPath src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
Invoke-Checked $javac (@('--release','17','-encoding','UTF-8','-cp',$compilePath,'-d','build/classes') + $sources)
Invoke-Checked $jar @('--create','--file','build/module.jar','-C','build/classes','.')

$testPath = 'tools/json-20240303.jar;build/classes;tools/libxposed-api-102.jar;tools/android-35.jar'
$tests = @(Get-ChildItem -LiteralPath src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
Invoke-Checked $javac (@('--release','17','-encoding','UTF-8','-cp',$testPath,'-d','build/test-classes') + $tests)
Invoke-Checked $java @('-cp',"build/test-classes;$testPath",'local.gamecenter.memory16.Memory16ModuleTest')

Invoke-Checked $java @('-cp','tools/r8-8.6.27.jar','com.android.tools.r8.D8',
    '--min-api','29','--lib','tools/android-35.jar','--classpath','tools/libxposed-api-102.jar',
    '--output','build/dex','build/module.jar')
Invoke-Checked 'tools/aapt2.exe' @('compile','--dir','src/main/res','-o','build/resources.zip')
Invoke-Checked 'tools/aapt2.exe' @('link','-I','tools/android-35.jar',
    '--manifest','src/main/AndroidManifest.xml','-o','build/base.apk','build/resources.zip')
Invoke-Checked 'python' @('tools/package_apk.py','package')

if (!(Test-Path -LiteralPath 'tools/memory16-local.p12')) {
    Invoke-Checked $keytool @('-genkeypair','-keystore','tools/memory16-local.p12','-storetype','PKCS12',
        '-storepass','android','-keypass','android','-alias','memory16','-keyalg','RSA','-keysize','3072',
        '-validity','10000','-dname','CN=Local GameCenter Memory16')
}
Invoke-Checked $java @('--class-path','tools/apksig-8.6.1.jar','tools/SignApk.java',
    'tools/memory16-local.p12','build/unsigned.apk','dist/GameCenterMemory16-v1.0-api102.apk')
Invoke-Checked 'python' @('tools/package_apk.py','verify')
Invoke-Checked 'tools/aapt2.exe' @('dump','badging','dist/GameCenterMemory16-v1.0-api102.apk')
Get-FileHash -LiteralPath 'dist/GameCenterMemory16-v1.0-api102.apk' -Algorithm SHA256
