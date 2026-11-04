param(
    [string]$DataDirectory = "D:\app\pgsql",
    [string]$PostgresBin = "D:\Program Files\PostgreSQL\18\bin",
    [string]$ServiceName = "postgresql-x64-18",
    [string]$Database = "travel_guide",
    [string]$ExtensionOwner = "travel"
)

$ErrorActionPreference = "Stop"
$hbaPath = Join-Path $DataDirectory "pg_hba.conf"
$original = [System.IO.File]::ReadAllText($hbaPath)
$utf8 = [System.Text.UTF8Encoding]::new($false)
$psql = Join-Path $PostgresBin "psql.exe"
$pgIsReady = Join-Path $PostgresBin "pg_isready.exe"

function Wait-Postgres {
    $deadline = (Get-Date).AddSeconds(30)
    do {
        & $pgIsReady -h localhost -p 5432 *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "PostgreSQL did not recover within 30 seconds"
}

try {
    # 只临时放开回环地址；操作结束后在 finally 中恢复原始 pg_hba.conf。
    $temporary = $original
        .Replace(
            "host    all             all             127.0.0.1/32            scram-sha-256",
            "host    all             all             127.0.0.1/32            trust"
        )
        .Replace(
            "host    all             all             ::1/128                 scram-sha-256",
            "host    all             all             ::1/128                 trust"
        )
    if ($temporary -eq $original) {
        throw "Could not locate expected pg_hba.conf host authentication lines"
    }
    [System.IO.File]::WriteAllText($hbaPath, $temporary, $utf8)
    Restart-Service -Name $ServiceName -Force
    Wait-Postgres

    & $psql -h localhost -U postgres -d $Database -w -v ON_ERROR_STOP=1 -Atc `
        "CREATE EXTENSION IF NOT EXISTS vector; ALTER EXTENSION vector OWNER TO $ExtensionOwner;"
    if ($LASTEXITCODE -ne 0) {
        throw "CREATE EXTENSION vector failed"
    }
}
finally {
    [System.IO.File]::WriteAllText($hbaPath, $original, $utf8)
    Restart-Service -Name $ServiceName -Force
    Wait-Postgres
}

& $psql -h localhost -U $ExtensionOwner -d $Database -Atc `
    "SELECT extname || ':' || extversion FROM pg_extension WHERE extname = 'vector';"
if ($LASTEXITCODE -ne 0) {
    throw "Could not verify pgvector installation"
}
