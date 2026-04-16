$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$files = Get-ChildItem -Path 'app/src/main/java' -Filter '*.kt' -Recurse
foreach ($f in $files) {
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
    if ($hasBom) {
        $bytes = $bytes[3..$bytes.Length]
    }
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    $hasIssue = $text -match 'import com\.sdk\.glassessdksample\.RemoteConfigManager \S'
    if ($hasBom -or $hasIssue) {
        $fixed = $text -replace 'import com\.sdk\.glassessdksample\.RemoteConfigManager (\S)', "import com.sdk.glassessdksample.RemoteConfigManager`r`nimport `$1"
        [System.IO.File]::WriteAllText($f.FullName, $fixed, $utf8NoBom)
        if ($hasBom) { Write-Host "Fixed BOM+import: $($f.Name)" }
        else { Write-Host "Fixed import: $($f.Name)" }
    }
}
Write-Host 'Done'
