@echo off
rem Roda o servidor de leitura na SUA rede (Windows). Precisa de Python 3.10+ e do poppler (pdftoppm) no PATH:
rem   https://github.com/oschwartz10612/poppler-windows/releases  (descompacte e ponha a pasta "bin" no PATH)
rem So aparelhos pareados (com o codigo que aparece aqui) conseguem usar. Nada e exposto na internet.
cd /d "%~dp0"
if not exist .venv (
  python -m venv .venv || goto erro
  .venv\Scripts\pip install --upgrade pip
  .venv\Scripts\pip install homr pillow || goto erro
  .venv\Scripts\homr --init
)
set PATH=%CD%\.venv\Scripts;%PATH%
set DISCOVERY=1
set BIND=0.0.0.0
echo Enderecos deste computador (use um destes no app, porta 8080):
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do echo   http://%%a:8080
python server.py
goto fim
:erro
echo Algo falhou na instalacao. Veja a mensagem acima.
:fim
pause
