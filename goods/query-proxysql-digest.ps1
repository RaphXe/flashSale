param(
    [string]$ContainerName = "flashsale-proxysql",
    [int]$Limit = 50,
    [string]$Keyword = ""
)

$ErrorActionPreference = "Stop"

function Convert-MySqlTabularOutput {
    param([string[]]$Lines)

    if (-not $Lines -or $Lines.Count -eq 0) {
        return @()
    }

    $headers = $Lines[0] -split "`t"
    $rows = @()

    for ($i = 1; $i -lt $Lines.Count; $i++) {
        if ([string]::IsNullOrWhiteSpace($Lines[$i])) {
            continue
        }

        $parts = $Lines[$i] -split "`t", $headers.Count
        $obj = [ordered]@{}

        for ($j = 0; $j -lt $headers.Count; $j++) {
            $name = $headers[$j]
            $value = if ($j -lt $parts.Count) { $parts[$j] } else { $null }
            $obj[$name] = $value
        }

        $rows += [pscustomobject]$obj
    }

    return $rows
}

$whereClause = ""
if (-not [string]::IsNullOrWhiteSpace($Keyword)) {
    $escaped = $Keyword.Replace("'", "''")
    $whereClause = " WHERE LOWER(digest_text) LIKE LOWER('%$escaped%')"
}

Write-Host "Running query in container '$ContainerName' ..." -ForegroundColor Cyan

$candidateTables = @("stats.stats_mysql_query_digest", "stats_mysql_query_digest")
$rows = @()
$usedTable = ""

foreach ($table in $candidateTables) {
    $query = "SELECT * FROM $table${whereClause} ORDER BY count_star DESC, sum_time DESC LIMIT $Limit;"
    $raw = docker exec $ContainerName mysql -uadmin -padmin -h localhost -P 6032 -B -e $query
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to execute mysql command in container '$ContainerName'."
    }

    $parsed = Convert-MySqlTabularOutput -Lines ($raw -split "`r?`n")
    if ($parsed -and $parsed.Count -gt 0) {
        $rows = $parsed
        $usedTable = $table
        break
    }
}

if (-not $rows -or $rows.Count -eq 0) {
    if (-not [string]::IsNullOrWhiteSpace($Keyword)) {
        $countRows = @()
        foreach ($table in $candidateTables) {
            $countQuery = "SELECT COUNT(*) AS total_count FROM $table;"
            $countRaw = docker exec $ContainerName mysql -uadmin -padmin -h localhost -P 6032 -B -e $countQuery
            if ($LASTEXITCODE -eq 0) {
                $countParsed = Convert-MySqlTabularOutput -Lines ($countRaw -split "`r?`n")
                if ($countParsed -and $countParsed.Count -gt 0) {
                    $countRows = $countParsed
                    $usedTable = $table
                    break
                }
            }
        }

        $totalCount = if ($countRows -and $countRows[0].PSObject.Properties.Name -contains "total_count") { $countRows[0].total_count } else { "unknown" }
        Write-Host "No rows matched keyword '$Keyword'." -ForegroundColor Yellow
        Write-Host "Digest table source: $usedTable, total rows without keyword: $totalCount" -ForegroundColor DarkYellow
        Write-Host "Tip: try broader keyword like 'insert', 'update', 'select' or run without -Keyword." -ForegroundColor DarkYellow
    } else {
        Write-Host "No rows found in digest tables (stats.stats_mysql_query_digest / stats_mysql_query_digest)." -ForegroundColor Yellow
        Write-Host "Hint: if you just queried stats_mysql_query_digest_reset, counters may have been cleared; run business traffic first." -ForegroundColor DarkYellow
    }
    exit 0
}

foreach ($row in $rows) {
    if ($null -ne $row.sum_time -and $row.sum_time -match '^\d+$') {
        $row | Add-Member -NotePropertyName sum_time_ms -NotePropertyValue ([math]::Round(([double]$row.sum_time / 1000.0), 3)) -Force
    }
    if ($null -ne $row.min_time -and $row.min_time -match '^\d+$') {
        $row | Add-Member -NotePropertyName min_time_ms -NotePropertyValue ([math]::Round(([double]$row.min_time / 1000.0), 3)) -Force
    }
    if ($null -ne $row.max_time -and $row.max_time -match '^\d+$') {
        $row | Add-Member -NotePropertyName max_time_ms -NotePropertyValue ([math]::Round(([double]$row.max_time / 1000.0), 3)) -Force
    }
    if ($null -ne $row.digest_text) {
        $preview = $row.digest_text
        if ($preview.Length -gt 100) {
            $preview = $preview.Substring(0, 100) + "..."
        }
        $row | Add-Member -NotePropertyName digest_preview -NotePropertyValue $preview -Force
    }
}

$preferredColumns = @(
    "hostgroup",
    "schemaname",
    "username",
    "count_star",
    "sum_time_ms",
    "min_time_ms",
    "max_time_ms",
    "sum_rows_affected",
    "sum_rows_sent",
    "digest_preview"
)

$existingColumns = $preferredColumns | Where-Object { $rows[0].PSObject.Properties.Name -contains $_ }

Write-Host "" 
Write-Host "Readable digest summary (top $($rows.Count), source: $usedTable)" -ForegroundColor Green
$rows | Select-Object $existingColumns | Format-Table -AutoSize

Write-Host ""
Write-Host "Top SQL digest (compact)" -ForegroundColor Green
foreach ($r in $rows) {
    $hg = if ($r.PSObject.Properties.Name -contains "hostgroup") { $r.hostgroup } else { "?" }
    $cnt = if ($r.PSObject.Properties.Name -contains "count_star") { $r.count_star } else { "?" }
    $sumMs = if ($r.PSObject.Properties.Name -contains "sum_time_ms") { $r.sum_time_ms } else { "?" }
    $sql = if ($r.PSObject.Properties.Name -contains "digest_preview") { $r.digest_preview } else { "" }
    Write-Host ("[HG={0}] count={1}, sum_ms={2}, sql={3}" -f $hg, $cnt, $sumMs, $sql)
}

Write-Host "" 
Write-Host "Tip: use -Keyword insert or -Keyword select to filter digest_text." -ForegroundColor DarkGray
