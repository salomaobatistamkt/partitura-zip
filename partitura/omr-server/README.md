# Servidor de leitura de PDF e foto (OMR) para o Partitura

O app Partitura lê MusicXML sozinho, mas reconhecer notas em **PDF ou foto** exige um reconhecedor óptico de música.
Este servidor usa o [homr](https://github.com/liebharc/homr) (licença AGPL-3.0): recebe o PDF/foto, devolve um MusicXML, e o app abre o resultado no Estúdio.

## O que foi testado (no ambiente de desenvolvimento, fora do Docker)
- homr instalado por pip, modelos baixados com `homr --init`.
- `server.py` recebendo PNG e PDF por POST e devolvendo MusicXML que o app abre (duas pautas, armadura, notas).
- Token (`?token=`) recusando pedidos sem senha; arquivo inválido recusado com erro claro.
- Partituras digitais limpas foram lidas sem erros. **Fotos de partitura têm mais erros** (luz, dobras, ângulo): sempre confira as notas depois de ler.

**Não testado:** o `docker build` em si (o ambiente de desenvolvimento não tem Docker). Os passos dele são os mesmos comandos que rodaram lá.

## Subir com Docker
```bash
cd omr-server
docker build -t partitura-omr .
docker run -d --name partitura-omr -p 8080:8080 -e OMR_TOKEN=troque-este-segredo --restart unless-stopped partitura-omr
```
Teste: `curl -X POST --data-binary @partitura.pdf "http://SEU-SERVIDOR:8080/omr?token=troque-este-segredo" -o saida.musicxml`

No **Coolify**: crie um recurso "Dockerfile" apontando para a pasta `omr-server`, porta 8080, e defina a variável `OMR_TOKEN`. Ponha um domínio com HTTPS.

## Ligar no app
Início → engrenagem (Ajustes) → "Servidor de leitura": `https://seu-dominio/omr?token=troque-este-segredo`.
Depois, **Abrir arquivo** → escolha o PDF ou a foto. A leitura leva de 10 s a 1–2 min por página (CPU).

## Limites
- PDF: até 12 páginas, 40 MB. As páginas são juntadas num só MusicXML.
- Um arquivo por vez (fila simples).
- Manuscrito não é suportado pelo homr.
