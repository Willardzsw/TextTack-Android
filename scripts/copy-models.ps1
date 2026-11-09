# 把 OCR 模型从 Windows 版仓库复制到 app/src/main/assets/ocr/，并校验 SHA-256。
# 只读取下面列出的 5 个模型文件，不碰 Windows 版的其他内容。
# 用法：powershell -ExecutionPolicy Bypass -File scripts\copy-models.ps1 [-Source E:\Claude\overtranslate]

param(
    [string]$Source = 'E:\Claude\overtranslate'
)

$ErrorActionPreference = 'Stop'
$modelRoot = Join-Path $Source 'src\OverTranslate\ocrmodels\onnx'
$target = Join-Path $PSScriptRoot '..\app\src\main\assets\ocr'

$files = @(
    @{ From = 'shared\det.onnx'; To = 'det\det.onnx';   Sha = '193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8' },
    @{ From = 'thai\rec.onnx';   To = 'thai\rec.onnx';  Sha = 'de541dd83161c241ff426f7ecfd602a0ba77d686cf3ab9a6c255ea82fd08006e' },
    @{ From = 'thai\dict.txt';   To = 'thai\dict.txt';  Sha = '57f5406f94bb6688fb7077f7be65f08bbd71cecf48c01ea26c522cb5c4836b7a' },
    @{ From = 'cjk\rec.onnx';    To = 'cjk\rec.onnx';   Sha = '5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634' },
    @{ From = 'cjk\dict.txt';    To = 'cjk\dict.txt';   Sha = 'b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d' }
)

foreach ($f in $files) {
    $src = Join-Path $modelRoot $f.From
    $dst = Join-Path $target $f.To
    if (-not (Test-Path $src)) { throw "找不到模型文件：$src" }
    $hash = (Get-FileHash -Algorithm SHA256 $src).Hash.ToLowerInvariant()
    if ($hash -ne $f.Sha) { throw "哈希不符：$src`n  期望 $($f.Sha)`n  实际 $hash" }
    New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
    Copy-Item $src $dst -Force
    Write-Host "已复制 $($f.To)"
}
Write-Host '模型已就绪。'
