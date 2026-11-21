param([switch]$Test)
$ErrorActionPreference = 'Stop'
$compiler = (Get-Command kotlinc.bat -ErrorAction SilentlyContinue).Source
if (-not $compiler) {
    $compiler = Join-Path $env:ProgramFiles 'Android\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat'
}
if (-not (Test-Path -LiteralPath $compiler)) {
    throw 'Kotlin compiler not found. Put kotlinc.bat (Kotlin 2.2) on PATH, or use the Android Studio bundled compiler.'
}
$shared = Join-Path $PSScriptRoot '..\app\src\main\kotlin\tel\misa\mxpocket'
$out = Join-Path $PSScriptRoot 'build'
$null = New-Item -ItemType Directory -Force $out
$jar = Join-Path $out 'mx-pocket-preview.jar'
$compilerHome = Split-Path (Split-Path $compiler)
$sources = @("$shared\actions\ActionDispatcher.kt", "$shared\deck\DeckModel.kt", "$shared\FocusTimer.kt",
    "$PSScriptRoot\PreviewDeck.kt", "$PSScriptRoot\PreviewWindow.kt")
& $compiler @sources -kotlin-home $compilerHome "-Dkotlin.home=$compilerHome" -jvm-target 17 -include-runtime -d $jar
if ($LASTEXITCODE -ne 0) { throw "Preview build failed: $LASTEXITCODE" }
Write-Output "Built $jar"
if ($Test) {
    $tests = Join-Path $out 'preview-tests.jar'
    & $compiler "$PSScriptRoot\PreviewDeckTest.kt" -kotlin-home $compilerHome -classpath $jar -jvm-target 17 -d $tests
    if ($LASTEXITCODE -ne 0) { throw "Preview test build failed: $LASTEXITCODE" }
    & java -cp "$tests;$jar" tel.misa.mxpocket.preview.PreviewDeckTestKt
    if ($LASTEXITCODE -ne 0) { throw "Preview test failed: $LASTEXITCODE" }
}
