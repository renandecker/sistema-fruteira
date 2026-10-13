# Garante senhas seguras no .env: valor vazio ou de exemplo ("troque-...") vira uma senha aleatoria.
# Uso: powershell -ExecutionPolicy Bypass -File preparar-env.ps1 [-Arquivo .env] [-ForcarProducao]
param([string]$Arquivo = '.env', [switch]$ForcarProducao)
$linhas = New-Object System.Collections.Generic.List[string]
Get-Content -Path $Arquivo | ForEach-Object { $linhas.Add($_) }
function Gerar([int]$n) {
  $c = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
  $b = New-Object byte[] $n
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($b); $rng.Dispose()
  -join ($b | ForEach-Object { $c[$_ % $c.Length] })
}
if ($ForcarProducao) {
  for ($j = 0; $j -lt $linhas.Count; $j++) { if ($linhas[$j] -match '^APP_ENV=') { $linhas[$j] = 'APP_ENV=producao' } }
}
foreach ($k in 'DB_PASS', 'KEYCLOAK_ADMIN_PASSWORD', 'KEYCLOAK_DB_PASS', 'TEF_TOKEN') {
  $n = 24; if ($k -eq 'TEF_TOKEN') { $n = 40 }
  $ativa = -1; $comentada = -1
  for ($j = 0; $j -lt $linhas.Count; $j++) {
    if ($linhas[$j] -match "^$k=") { $ativa = $j }
    elseif ($comentada -lt 0 -and $linhas[$j] -match "^#$k=") { $comentada = $j }
  }
  if ($ativa -ge 0) {
    $v = ($linhas[$ativa] -replace "^$k=", '').Trim()
    if ($v -eq '' -or $v -like 'troque*') { $linhas[$ativa] = "$k=" + (Gerar $n); Write-Host "[SENHA] $k estava vazio/de exemplo: gerada senha aleatoria e gravada no .env (guarde o arquivo!)" }
  } else {
    if ($comentada -ge 0) { $linhas[$comentada] = "$k=" + (Gerar $n) } else { $linhas.Add("$k=" + (Gerar $n)) }
    Write-Host "[SENHA] $k ausente: gerada senha aleatoria e gravada no .env (guarde o arquivo!)"
  }
}
Set-Content -Path $Arquivo -Value $linhas -Encoding ASCII
