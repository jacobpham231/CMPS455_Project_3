//this script helps run Task 1 and Task 2 with the same codebase. It compiles the Java code and then runs it with the provided scheduler arguments. If no scheduler arguments are provided, it shows the application usage.
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSCommandPath
$srcRoot = Join-Path $repoRoot 'Task_1_2\src'
$mainJava = Join-Path $srcRoot 'com\company\Main.java'

function Normalize-SchedulerArgs {
    param(
        [object[]]$RawArgs
    )

    $normalized = New-Object System.Collections.Generic.List[string]
    $i = 0

    while ($i -lt $RawArgs.Count) {
        $current = $RawArgs[$i]
        $currentText = [string]$current

        if ($currentText -eq '-B') {
            $normalized.Add('-B')
            $i++

            if ($i -ge $RawArgs.Count) {
                throw 'Missing burst list after -B'
            }

            $burstParts = New-Object System.Collections.Generic.List[string]

            while ($i -lt $RawArgs.Count) {
                $next = $RawArgs[$i]

                if ($next -is [System.Array]) {
                    foreach ($item in $next) {
                        $burstParts.Add([string]$item)
                    }
                    $i++
                    continue
                }

                $nextText = [string]$next
                if ($burstParts.Count -gt 0 -and $nextText.StartsWith('-')) {
                    break
                }

                if ($burstParts.Count -eq 0 -and $nextText.StartsWith('-')) {
                    throw 'Missing burst list after -B'
                }

                $burstParts.Add($nextText)
                $i++
            }

            $normalized.Add(($burstParts -join ','))
            continue
        }

        if ($current -is [System.Array]) {
            foreach ($item in $current) {
                $normalized.Add([string]$item)
            }
        } else {
            $normalized.Add($currentText)
        }

        $i++
    }

    return $normalized.ToArray()
}

if (!(Test-Path -LiteralPath $mainJava)) {
    Write-Error "Main.java not found at: $mainJava"
    exit 1
}

Write-Host "[run-task1] Compiling..."
& javac $mainJava
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$javaArgs = @('-cp', $srcRoot, 'com.company.Main')
$schedulerArgs = Normalize-SchedulerArgs -RawArgs $args

if ($schedulerArgs.Count -gt 0) {
    $javaArgs += $schedulerArgs
} else {
    Write-Host "[run-task1] No scheduler args provided. Showing app usage..."
    $javaArgs += '--help'
}

Write-Host ("[run-task1] Running: java " + ($javaArgs -join ' '))
& java @javaArgs
exit $LASTEXITCODE
