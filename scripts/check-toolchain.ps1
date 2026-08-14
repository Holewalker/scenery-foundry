function Get-PythonVersionLine([object[]]$Lines) {
    $Lines |
        ForEach-Object { $_.ToString().Trim() } |
        Where-Object { $_ -match '^Python 3\.14\.' } |
        Select-Object -First 1
}
