"""Servidor de leitura de partitura (OMR) para o app Partitura.

POST /omr  (corpo = bytes de um PDF, PNG ou JPEG)  ->  200 + MusicXML
Usa o homr (https://github.com/liebharc/homr, AGPL-3.0) para reconhecer as notas.
Se OMR_TOKEN estiver definido, a URL precisa terminar com ?token=SEGREDO.
"""
import glob
import os
import subprocess
import tempfile
import threading
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

TOKEN = os.environ.get("OMR_TOKEN", "")
MAX_BYTES = 40 * 1024 * 1024
LOCK = threading.Lock()  # o reconhecimento é pesado: uma partitura por vez


def run(cmd, timeout):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def homr_image(path):
    r = run(["homr", path], 900)
    out = os.path.splitext(path)[0] + ".musicxml"
    if not os.path.exists(out):
        raise RuntimeError("não encontrei notas na imagem: " + (r.stderr or r.stdout or "")[-300:])
    return out


def merge(files):
    """Junta os compassos de várias páginas, parte por parte."""
    base = ET.parse(files[0])
    root = base.getroot()
    parts = root.findall("part")
    for f in files[1:]:
        other = ET.parse(f).getroot().findall("part")
        for k, part in enumerate(parts):
            if k >= len(other):
                break
            n = len(part.findall("measure"))
            for m in other[k].findall("measure"):
                n += 1
                m.set("number", str(n))
                part.append(m)
    from io import BytesIO
    buf = BytesIO()
    base.write(buf, encoding="utf-8", xml_declaration=True)
    return buf.getvalue()


def recognize(data):
    with tempfile.TemporaryDirectory() as tmp:
        if data[:4] == b"%PDF":
            pdf = os.path.join(tmp, "in.pdf")
            open(pdf, "wb").write(data)
            r = run(["pdftoppm", "-r", "200", "-png", pdf, os.path.join(tmp, "page")], 300)
            pages = sorted(glob.glob(os.path.join(tmp, "page*.png")))
            if not pages:
                raise RuntimeError("não consegui abrir o PDF: " + (r.stderr or "")[-200:])
            outs = [homr_image(p) for p in pages[:12]]
            return merge(outs)
        if data[:8] == b"\x89PNG\r\n\x1a\n":
            ext = "png"
        elif data[:2] == b"\xff\xd8":
            ext = "jpg"
        else:
            raise ValueError("formato não suportado (use PDF, PNG ou JPEG)")
        img = os.path.join(tmp, "in." + ext)
        open(img, "wb").write(data)
        return open(homr_image(img), "rb").read()


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="text/plain; charset=utf-8"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _auth(self):
        if not TOKEN:
            return True
        q = parse_qs(urlparse(self.path).query)
        return q.get("token", [""])[0] == TOKEN

    def do_GET(self):
        self._send(200, "Servidor OMR do Partitura no ar. Envie um POST em /omr com o PDF ou a foto.")

    def do_POST(self):
        if not self._auth():
            return self._send(401, "token inválido")
        n = int(self.headers.get("Content-Length", "0"))
        if n <= 0 or n > MAX_BYTES:
            return self._send(413, "arquivo vazio ou maior que 40 MB")
        data = self.rfile.read(n)
        try:
            with LOCK:
                xml = recognize(data)
            self._send(200, xml, "application/vnd.recordare.musicxml+xml")
        except ValueError as e:
            self._send(415, str(e))
        except Exception as e:  # noqa: BLE001
            self._send(422, "falha na leitura: %s" % e)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    print("OMR ouvindo na porta", port, flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
