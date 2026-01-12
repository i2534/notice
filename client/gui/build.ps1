# Notice GUI - Windows Build Script
# Usage: .\build.ps1

$ErrorActionPreference = "Stop"

Write-Host "Notice GUI Build Script" -ForegroundColor Cyan
Write-Host ""

# Check environment
Write-Host "Checking environment..." -ForegroundColor Yellow

# Check Node.js
if (-not (Get-Command "node" -ErrorAction SilentlyContinue)) {
    Write-Host "Error: Node.js not found" -ForegroundColor Red
    Write-Host "Download: https://nodejs.org/" -ForegroundColor Gray
    exit 1
}
Write-Host "  Node.js: $(node --version)" -ForegroundColor Green

# Check Rust
if (-not (Get-Command "rustc" -ErrorAction SilentlyContinue)) {
    Write-Host "Error: Rust not found" -ForegroundColor Red
    Write-Host "Download: https://rustup.rs/" -ForegroundColor Gray
    exit 1
}
Write-Host "  Rust: $(rustc --version)" -ForegroundColor Green

# Check CMake
if (-not (Get-Command "cmake" -ErrorAction SilentlyContinue)) {
    Write-Host "Error: CMake not found" -ForegroundColor Red
    Write-Host "Install: winget install Kitware.CMake" -ForegroundColor Gray
    Write-Host "Or download: https://cmake.org/download/" -ForegroundColor Gray
    exit 1
}
Write-Host "  CMake: $(cmake --version | Select-Object -First 1)" -ForegroundColor Green

# Install dependencies
Write-Host ""
Write-Host "Installing dependencies..." -ForegroundColor Yellow
npm install
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Build
Write-Host ""
Write-Host "Building..." -ForegroundColor Yellow
npm run build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Package
Write-Host ""
Write-Host "Packaging..." -ForegroundColor Yellow

$outputDir = "dist"
$exePath = "tauri/target/release/notice-gui.exe"
$zipName = "notice-gui-windows-amd64.zip"

if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}

if (Test-Path $exePath) {
    Compress-Archive -Path $exePath -DestinationPath "$outputDir/$zipName" -Force
    Write-Host ""
    Write-Host "Build completed!" -ForegroundColor Green
    Write-Host "Output: $outputDir/$zipName" -ForegroundColor Cyan
} else {
    Write-Host "Error: Executable not found at $exePath" -ForegroundColor Red
    exit 1
}
