$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir   = Split-Path -Parent $ScriptDir

$VoskCacheDir  = Join-Path $RootDir ".tools\vosk"
$VoskModelUrl  = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
$VoskModelSha  = "efa092d280153a77615e9e0c7d7283e93e600de3d19d3bec686c57ef19d52eac"
$VoskModelName = "vosk-model-small-ja-0.22.zip"
$VoskModelDir  = "vosk-model-small-ja-0.22"

$JuliusCommit = "1ceb4dec245ef482918ca33c55c71d383dce145e"
$JuliusRepo   = "https://github.com/julius-speech/dictation-kit.git"

$JuliusCacheDir     = Join-Path $RootDir ".tools\julius"
$JuliusCheckoutDir  = Join-Path $JuliusCacheDir "dictation-kit"

$JuliusModels = [ordered]@{
    "jnas-tri-3k16-gid.binhmm" = "5f427fa11189a4d49de9a9ef51e8b1159971ee1cf5e3656f42081cf69dbf0c98"
    "logicalTri-3k16-gid.bin"   = "bb4673040fd2691b53dea5bc411dead26732336a5c997fb0f86f93818bbfac66"
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)

    & git @Args
    if ($LASTEXITCODE -ne 0) {
        throw "git command failed: git $($Args -join ' ')"
    }
}

function Fetch-Verified(
    [string]$Url,
    [string]$Destination,
    [string]$ExpectedSha
) {
    if (Test-Path -LiteralPath $Destination) {
        if ((Get-Sha256 $Destination) -eq $ExpectedSha.ToLowerInvariant()) {
            Write-Host "fetch-deps: using cached $(Split-Path $Destination -Leaf)"
            return
        }
    }

    Write-Host "fetch-deps: downloading $(Split-Path $Destination -Leaf)"

    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue

    if ($curl) {
        & $curl.Source -fL --retry 3 -o $Destination $Url

        if ($LASTEXITCODE -ne 0) {
            throw "Download failed: $Url"
        }
    }
    else {
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Destination
    }

    if ((Get-Sha256 $Destination) -ne $ExpectedSha.ToLowerInvariant()) {
        throw "SHA256 mismatch for $(Split-Path $Destination -Leaf)"
    }
}

function Verify-SumFile([string]$SumFile) {
    foreach ($Line in Get-Content -LiteralPath $SumFile) {
        if ([string]::IsNullOrWhiteSpace($Line) -or $Line.TrimStart().StartsWith("#")) {
            continue
        }

        if ($Line -notmatch '^\s*([0-9A-Fa-f]{64})\s+\*?(.+?)\s*$') {
            throw "Invalid SHA256SUMS line in $SumFile"
        }

        $Expected = $Matches[1].ToLowerInvariant()
        $Relative = $Matches[2].Replace("/", "\")
        $Target   = Join-Path $RootDir $Relative

        if (-not (Test-Path -LiteralPath $Target)) {
            throw "Missing checksum target: $Relative"
        }

        if ((Get-Sha256 $Target) -ne $Expected) {
            throw "SHA256 mismatch: $Relative"
        }

        Write-Host "$Relative`: OK"
    }
}

# ------------------------------------------------------------
# Vosk
# ------------------------------------------------------------

New-Item -ItemType Directory -Force $VoskCacheDir | Out-Null

$VoskZip = Join-Path $VoskCacheDir $VoskModelName
Fetch-Verified $VoskModelUrl $VoskZip $VoskModelSha

$VoskAssetsDir = Join-Path $RootDir "app\src\main\assets\vosk"
New-Item -ItemType Directory -Force $VoskAssetsDir | Out-Null

$VoskExtracted = Join-Path $VoskAssetsDir $VoskModelDir

Remove-Item `
    -LiteralPath $VoskExtracted `
    -Recurse `
    -Force `
    -ErrorAction SilentlyContinue

Expand-Archive `
    -LiteralPath $VoskZip `
    -DestinationPath $VoskAssetsDir `
    -Force

# ------------------------------------------------------------
# Julius Dictation Kit
#
# The required acoustic-model files are Git LFS objects.
# raw.githubusercontent.com returns only LFS pointer files,
# so fetch the pinned repository commit and pull only model/phone_m.
# ------------------------------------------------------------

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "git is required to fetch the Julius Dictation Kit"
}

& git lfs version *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Git LFS is required to fetch the Julius Dictation Kit"
}

New-Item -ItemType Directory -Force $JuliusCacheDir | Out-Null

$needsCheckout = $true

if (Test-Path -LiteralPath (Join-Path $JuliusCheckoutDir ".git")) {
    try {
        $current = (& git -C $JuliusCheckoutDir rev-parse HEAD).Trim()

        if ($LASTEXITCODE -eq 0 -and $current -eq $JuliusCommit) {
            $needsCheckout = $false
            Write-Host "fetch-deps: using pinned Julius checkout $JuliusCommit"
        }
    }
    catch {
        $needsCheckout = $true
    }
}

if ($needsCheckout) {
    Remove-Item `
        -LiteralPath $JuliusCheckoutDir `
        -Recurse `
        -Force `
        -ErrorAction SilentlyContinue

    Write-Host "fetch-deps: cloning pinned Julius Dictation Kit"

    # Prevent Git LFS from downloading unrelated large objects during checkout.
    $oldSkipSmudge = $env:GIT_LFS_SKIP_SMUDGE
    $env:GIT_LFS_SKIP_SMUDGE = "1"

    try {
        Invoke-Git `
            clone `
            --filter=blob:none `
            --no-checkout `
            $JuliusRepo `
            $JuliusCheckoutDir

        Invoke-Git `
            -C $JuliusCheckoutDir `
            sparse-checkout `
            init `
            --cone

        Invoke-Git `
            -C $JuliusCheckoutDir `
            sparse-checkout `
            set `
            model/phone_m

        Invoke-Git `
            -C $JuliusCheckoutDir `
            fetch `
            --depth 1 `
            origin `
            $JuliusCommit

        Invoke-Git `
            -C $JuliusCheckoutDir `
            checkout `
            --detach `
            FETCH_HEAD
    }
    finally {
        $env:GIT_LFS_SKIP_SMUDGE = $oldSkipSmudge
    }
}

$current = (& git -C $JuliusCheckoutDir rev-parse HEAD).Trim()

if ($LASTEXITCODE -ne 0 -or $current -ne $JuliusCommit) {
    throw "Julius checkout is not at pinned commit $JuliusCommit"
}

Write-Host "fetch-deps: pulling Julius Git LFS model objects"

Invoke-Git `
    -C $JuliusCheckoutDir `
    lfs `
    pull `
    --include="model/phone_m/*" `
    --exclude=""

$JuliusAssetsDir = Join-Path $RootDir "app\src\main\assets\julius\model"
New-Item -ItemType Directory -Force $JuliusAssetsDir | Out-Null

foreach ($Name in $JuliusModels.Keys) {
    $Source = Join-Path $JuliusCheckoutDir "model\phone_m\$Name"

    if (-not (Test-Path -LiteralPath $Source)) {
        throw "Missing Julius model file: $Name"
    }

    $Actual = Get-Sha256 $Source
    $Expected = $JuliusModels[$Name].ToLowerInvariant()

    if ($Actual -ne $Expected) {
        throw "SHA256 mismatch for Julius model: $Name"
    }

    Write-Host "fetch-deps: verified $Name"

    Copy-Item `
        -LiteralPath $Source `
        -Destination (Join-Path $JuliusAssetsDir $Name) `
        -Force
}

# Remove obsolete raw-download pointer files from older attempts, if present.
foreach ($Name in $JuliusModels.Keys) {
    $OldCache = Join-Path $JuliusCacheDir $Name

    if (Test-Path -LiteralPath $OldCache) {
        Remove-Item -LiteralPath $OldCache -Force
    }
}

# ------------------------------------------------------------
# Final source-of-truth checksum verification
# ------------------------------------------------------------

Verify-SumFile (Join-Path $RootDir "third_party\vosk\SHA256SUMS")
Verify-SumFile (Join-Path $RootDir "third_party\julius\SHA256SUMS")

Write-Host "fetch-deps: OK - the Vosk and Julius models are present and verified"