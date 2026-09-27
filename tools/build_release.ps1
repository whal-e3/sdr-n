param(
    [string]$SigningDirectory = "$env:LOCALAPPDATA\OrbitScope\signing",
    [string[]]$GradleTasks = @(':app:bundleRelease', ':app:assembleRelease')
)
$ErrorActionPreference = 'Stop'
$secretPath = Join-Path $SigningDirectory 'upload-password.xml'
$keystorePath = Join-Path $SigningDirectory 'orbitscope-upload.jks'
if (!(Test-Path $secretPath) -or !(Test-Path $keystorePath)) {
    throw 'Private upload signing files are missing. See docs/release/SIGNING.md.'
}
$secure = Import-Clixml -LiteralPath $secretPath
$previous = @{}
foreach ($name in @('ORBIT_UPLOAD_KEYSTORE', 'ORBIT_UPLOAD_KEY_ALIAS',
                   'ORBIT_UPLOAD_STORE_PASSWORD', 'ORBIT_UPLOAD_KEY_PASSWORD')) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $env:ORBIT_UPLOAD_KEYSTORE = $keystorePath
    $env:ORBIT_UPLOAD_KEY_ALIAS = 'orbitscope-upload'
    $env:ORBIT_UPLOAD_STORE_PASSWORD = $password
    $env:ORBIT_UPLOAD_KEY_PASSWORD = $password
    Push-Location (Split-Path $PSScriptRoot -Parent)
    try {
        & .\gradlew.bat @GradleTasks
        if ($LASTEXITCODE -ne 0) { throw "Release build failed: $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $password = $null
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
