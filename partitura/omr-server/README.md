# Servidor de leitura de PDF e foto (OMR) para o Partitura

O app Partitura lê MusicXML sozinho, mas reconhecer notas em **PDF ou foto** exige um reconhecedor óptico de música.
Este servidor usa o [homr](https://github.com/liebharc/homr) (licença AGPL-3.0): recebe o PDF/foto, devolve um MusicXML, e o app abre o resultado no Estúdio.

## O que foi testado (no ambiente de desenvolvimento, fora do Docker)
- homr instalado por pip, modelos baixados com `homr --init`.
- `server.py` recebendo PNG e PDF por POST e devolvendo MusicXML que o app abre (duas pautas, armadura, notas).
- Chave de acesso recusando pedidos sem senha (`/check` e `/omr`); `/health` respondendo; arquivo inválido recusado com erro claro.
- Partituras digitais limpas foram lidas sem erros. **Fotos de partitura têm mais erros** (luz, dobras, ângulo): sempre confira as notas depois de ler.

**Não testado:** o `docker build` em si (o ambiente de desenvolvimento não tem Docker). Os passos dele são os mesmos comandos que rodaram lá.

## Subir com Docker
```bash
cd omr-server
OMR_TOKEN=troque-este-segredo docker compose up -d --build
```
ou, sem compose:
```bash
docker build -t partitura-omr .
docker run -d --name partitura-omr -p 8080:8080 -e OMR_TOKEN=troque-este-segredo --restart unless-stopped partitura-omr
```
Testes: `curl http://SEU-SERVIDOR:8080/health` (deve responder `{"ok": true, ...}`) e
`curl -X POST -H "Authorization: Bearer troque-este-segredo" --data-binary @partitura.pdf http://SEU-SERVIDOR:8080/omr -o saida.musicxml`

### Coolify (passo a passo)
1. Projects → seu projeto → **+ New** → **Public Repository** (o repositório `salomaobatistamkt/partitura-zip` é público).
2. Repositório `https://github.com/salomaobatistamkt/partitura-zip`, branch `main`, **Build Pack: Dockerfile**.
3. **Base Directory:** `/partitura/omr-server` · porta exposta **8080**.
4. **Environment Variables:** `OMR_TOKEN` = uma chave longa (o app tem o botão "Gerar chave").
5. **Domains:** `https://omr.seudominio.com` (DNS apontando para o servidor; se usar Cloudflare, deixe **sem proxy**, pois pedidos acima de 100 s são cortados).
6. **Deploy.** A primeira montagem baixa os modelos (~100 MB) e leva alguns minutos.

## Ligar no app
Menu **Servidor** → endereço (`https://omr.seudominio.com`, sem `/omr`) e a chave → **Salvar e testar conexão**.
Depois, **Início → Abrir arquivo** → escolha o PDF ou a foto. A leitura leva de 10 s a 1–2 min por página (CPU).

## API
- `GET /health` → `{"ok": true, "needs_token": true|false, "homr": "versão", "busy": false}` (sem chave)
- `GET /check` → 200 se a chave confere, 401 se não
- `POST /omr` → corpo = bytes do PDF/PNG/JPEG; resposta = MusicXML. Chave: `Authorization: Bearer SEGREDO` (ou `?token=SEGREDO`).

## Limites
- PDF: até 12 páginas, 40 MB. As páginas são juntadas num só MusicXML.
- Um arquivo por vez (fila simples).
- Manuscrito não é suportado pelo homr.
