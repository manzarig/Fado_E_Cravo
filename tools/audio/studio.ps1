# Starts the Estudio (tools/audio/studio.py), creating the audio venv on the first run.
# Right-click > "Run with PowerShell", or from a terminal:  powershell -ExecutionPolicy Bypass -File tools\audio\studio.ps1
# Needs Python 3.10+ (the "py" launcher from python.org) and ffmpeg on PATH (winget install Gyan.FFmpeg).
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    Write-Host "ffmpeg is not on PATH. Install it with:  winget install Gyan.FFmpeg   then open a new terminal." -ForegroundColor Red
    Read-Host "Press Enter to close"
    exit 1
}
$python = Join-Path $here ".venv\Scripts\python.exe"
if (-not (Test-Path $python)) {
    Write-Host "First run: creating tools\audio\.venv and installing the pipeline (a few minutes)..."
    py -3 -m venv (Join-Path $here ".venv")
    & $python -m pip install -q -r (Join-Path $here "requirements.txt")
}
& $python -I (Join-Path $here "studio.py") @args
