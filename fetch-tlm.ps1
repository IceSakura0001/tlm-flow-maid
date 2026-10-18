$ErrorActionPreference = 'Stop'
$dependencyDir = Join-Path $PSScriptRoot 'libs'
$target = Join-Path $dependencyDir 'touhoulittlemaid-1.5.3.jar'
$expected = '12f29c14e806bf3aeb4f8399ac02199b419426905a27c3b9c7e019ef9daace09bc40d9b2c5c41ee730ce4b67eda5d0e93e8a8f32879b96b70e6003952c651716'
New-Item -ItemType Directory -Path $dependencyDir -Force | Out-Null
if (!(Test-Path -LiteralPath $target)) {
    & curl.exe --fail --location --connect-timeout 15 --max-time 180 --output $target 'https://cdn.modrinth.com/data/R0bDWFAW/versions/g1SKoGQJ/touhoulittlemaid-1.5.3-forge%2Bmc1.20.1.jar'
    if ($LASTEXITCODE -ne 0) { throw 'TLM download failed. Remove the incomplete file and retry.' }
}
if ((Get-FileHash -LiteralPath $target -Algorithm SHA512).Hash.ToLowerInvariant() -ne $expected) {
    throw 'TLM checksum mismatch. The file has not been accepted; replace it with the pinned official release.'
}
Write-Output 'TLM 1.5.3 official dependency verified (SHA512).'
