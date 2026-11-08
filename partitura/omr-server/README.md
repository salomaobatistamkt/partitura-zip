# Servidor de leitura de PDF e foto (OMR) do Compasso

O Compasso lê MusicXML sozinho; reconhecer notas em **PDF ou foto** exige um reconhecedor óptico de música.
Este servidor usa o [homr](https://github.com/liebharc/homr) (AGPL-3.0): recebe o arquivo, lê página por página,
junta tudo num só MusicXML e o app abre o resultado no Estúdio.

## Privacidade (o desenho é "só eu")
- **Nada funciona sem pareamento.** O servidor mostra um código de 8 letras (vale 10 min, uso único). No app:
  *Ajustes → Servidor privado → Parear*. Você recebe uma chave própria do aparelho; o servidor guarda só o hash dela.
- No app, a tela do servidor só abre com o **bloqueio do tablet** (ou um PIN do app) e a chave fica **criptografada no Keystore** do Android.
- 5 tentativas erradas bloqueiam o endereço por 15 min. `/health` só diz "ok".
- Rode **na sua rede** (abaixo). Não é preciso abrir porta nenhuma na internet. Para usar fora de casa, o jeito seguro é uma VPN
  privada como [Tailscale](https://tailscale.com) (grátis para uso pessoal): instale no computador e no tablet e use o endereço 100.x.x.x do computador.
- Se publicar na internet de qualquer forma, use HTTPS e mantenha o pareamento; não compartilhe o código.

## Rodar no seu computador (rede local)
- **Windows:** dê dois cliques em `run-local.bat` (precisa de Python 3.10+ e do poppler no PATH — o próprio arquivo explica).
- **Linux/macOS:** `./run-local.sh`
Na primeira vez ele instala o homr e baixa os modelos (alguns minutos). Depois mostra os endereços do computador e o **código de pareamento**.
No app: *Ajustes → Servidor privado → Procurar na rede* (ou digite `http://ENDEREÇO:8080`) → digite o código.
Outro código: `python server.py --new-code` · desconectar todos os aparelhos: `python server.py --revoke-all` · listar: `python server.py --list`.

## Rodar com Docker
```bash
cd omr-server
docker compose up -d --build
docker compose logs omr          # mostra o código de pareamento
docker compose exec omr python server.py --new-code   # novo código
```
Por padrão só o próprio computador alcança a porta (`127.0.0.1:8080`). Para a rede de casa, troque a linha `ports` por `"8080:8080"`.
(Em Docker a descoberta automática por UDP não atravessa a rede; digite o endereço.)

## API
- `GET /health` → `{"ok": true, "app": "compasso-omr"}`
- `POST /pair` `{"code","device"}` → `{"token"}`
- `GET /check`, `POST /unpair` (header `Authorization: Bearer CHAVE`)
- `POST /jobs` (corpo = bytes) → `202 {"id"}` · `GET /jobs/<id>` → `{status, stage, page, pages}` · `GET /jobs/<id>/result` → MusicXML
- `POST /omr` → modo antigo, síncrono.
- Variáveis: `PORT`, `BIND`, `DATA_DIR`, `DISCOVERY=1`, `OMR_TOKEN` (chave mestra opcional), `OMR_FAKE=1` (só testes).

## Limites e honestidade
- PDF: até 12 páginas, 40 MB. Um arquivo por vez (fila). Manuscrito não é suportado pelo homr.
- Partituras digitais limpas são lidas bem; **fotos têm mais erros** (luz, dobras, ângulo) — sempre confira as notas.
- O pré-processamento de fotos (cinza, contraste, tamanho) usa a biblioteca Pillow; sem ela, a imagem segue como está.
- O `docker build` e o app no tablet não foram executados no ambiente de desenvolvimento; o servidor foi testado de ponta a ponta
  em modo de teste (`OMR_FAKE=1`): pareamento, bloqueio, trabalhos, resultado, códigos novos.
