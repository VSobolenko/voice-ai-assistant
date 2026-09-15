# Создаёт виртуальное окружение и ставит зависимости.
# Запуск:  powershell -ExecutionPolicy Bypass -File .\setup.ps1
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Test-Path '.venv')) {
    Write-Host 'Создаю виртуальное окружение .venv ...'
    py -m venv .venv
}

Write-Host 'Ставлю зависимости ...'
.\.venv\Scripts\python.exe -m pip install --upgrade pip --quiet
.\.venv\Scripts\python.exe -m pip install -r requirements.txt

if (-not (Test-Path '.env')) {
    Copy-Item '.env.example' '.env'
    Write-Host 'Создан .env — впишите туда DEEPSEEK_API_KEY.'
}

Write-Host ''
Write-Host 'Готово. Дальше:'
Write-Host '  1) откройте .env и вставьте ключ DeepSeek'
Write-Host '  2) .\.venv\Scripts\python.exe check.py   # проверить всё'
Write-Host '  3) .\.venv\Scripts\python.exe main.py    # запустить диалог'
