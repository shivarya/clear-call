# Downloads the gitignored ML binaries the app needs to build and run "Isolate a voice":
#   - sherpa-onnx Android AAR (speaker embedding + Silero VAD runtimes, Apache-2.0)
#   - WeSpeaker CAM++ speaker-embedding ONNX model (en, VoxCeleb)
#   - Silero VAD ONNX model (MIT)
# Idempotent: skips files that already exist. Run from anywhere.

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$mobile = Split-Path -Parent $PSScriptRoot
$libs = Join-Path $mobile "app\libs"
$models = Join-Path $mobile "app\src\main\assets\models"
New-Item -ItemType Directory -Force $libs | Out-Null
New-Item -ItemType Directory -Force $models | Out-Null

$assets = @(
    @{
        Url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.3/sherpa-onnx-static-link-onnxruntime-1.13.3.aar"
        Out = Join-Path $libs "sherpa-onnx-static-link-onnxruntime-1.13.3.aar"
    },
    @{
        Url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_CAM%2B%2B.onnx"
        Out = Join-Path $models "wespeaker_en_voxceleb_CAM++.onnx"
    },
    @{
        Url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        Out = Join-Path $models "silero_vad.onnx"
    }
)

foreach ($a in $assets) {
    if (Test-Path $a.Out) {
        Write-Host "OK (exists): $($a.Out)"
    } else {
        Write-Host "Downloading $($a.Url)"
        Invoke-WebRequest $a.Url -OutFile $a.Out
        Write-Host "Saved: $($a.Out)"
    }
}
