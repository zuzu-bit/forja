@echo off
setlocal
cd /d "%~dp0"
echo FORJA - Pregatire publicare pe Cloudflare
echo Se descarca Node.js portabil, verificat de pe nodejs.org, daca lipseste.
echo Nu sunt necesare drepturi de administrator.
powershell.exe -NoProfile -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $a='x64'; if(($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') -or ($env:PROCESSOR_ARCHITEW6432 -eq 'ARM64')){$a='arm64'}; $base='https://nodejs.org/dist/latest-v24.x'; $tools=Join-Path $env:LOCALAPPDATA 'FORJA-Publicare'; New-Item -ItemType Directory -Force -Path $tools | Out-Null; $checks=(Invoke-WebRequest -UseBasicParsing -Uri ($base+'/SHASUMS256.txt') -TimeoutSec 60).Content; $m=[regex]::Match($checks,'(?m)^([0-9a-f]{64})\s+(node-v24\.\d+\.\d+-win-'+$a+'\.zip)\r?$'); if(-not $m.Success){throw 'Lipseste suma de verificare Node.js'}; $zipName=$m.Groups[2].Value; $name=$zipName.Substring(0,$zipName.Length-4); $zip=Join-Path $tools $zipName; if((-not (Test-Path $zip)) -or ((Get-FileHash -Algorithm SHA256 -LiteralPath $zip).Hash.ToLower() -ne $m.Groups[1].Value)){Invoke-WebRequest -UseBasicParsing -Uri ($base+'/'+$zipName) -OutFile $zip -TimeoutSec 300}; if((Get-FileHash -Algorithm SHA256 -LiteralPath $zip).Hash.ToLower() -ne $m.Groups[1].Value){throw 'Arhiva Node.js nu a trecut verificarea SHA256'}; Expand-Archive -LiteralPath $zip -DestinationPath $tools -Force; $node=Join-Path (Join-Path $tools $name) 'node.exe'; $env:PATH=(Split-Path $node)+';'+$env:PATH; & $node (Join-Path (Get-Location) 'publish.mjs'); exit $LASTEXITCODE"
if errorlevel 1 (
  echo.
  echo Publicarea s-a oprit. Pastreaza mesajul de eroare de mai sus.
) else (
  echo.
  echo Procesul de publicare s-a incheiat. Verifica mesajul GATA din fereastra.
)
echo.
pause
