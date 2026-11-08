"""Servidor de leitura de partitura (OMR) do app Compasso — PRIVADO por padrão.

Como a privacidade funciona
  * Nada é aceito sem uma chave. A chave não é digitada à mão: o app se PAREIA com o servidor usando um
    código de 8 letras que aparece só no console / no arquivo data/pair_code.txt (vale 10 minutos, uso único).
  * Cada aparelho pareado recebe uma chave própria (guardada aqui só como hash SHA-256 em data/devices.json).
  * Tentativas erradas bloqueiam o endereço por 15 minutos.
  * /health só diz "ok": não revela versão, estado nem se há chave.

Endpoints
  GET  /health            -> {"ok": true, "app": "compasso-omr"}
  POST /pair              -> corpo {"code": "ABCD-2345", "device": "Redmi Pad 2"}  =>  {"token": "..."}
  GET  /check             -> 200 se a chave confere (Authorization: Bearer CHAVE)
  POST /unpair            -> remove a chave deste aparelho
  POST /jobs              -> corpo = bytes de PDF/PNG/JPEG  =>  202 {"id": "..."}
  GET  /jobs/<id>         -> {"status": "queued|running|done|error", "stage": "...", "page": 1, "pages": 3, ...}
  GET  /jobs/<id>/result  -> MusicXML (quando status = done)
  POST /omr               -> modo antigo, síncrono (corpo = bytes), devolve MusicXML

Variáveis de ambiente: PORT (8080), BIND (0.0.0.0; use 127.0.0.1 para só este computador), DATA_DIR (./data),
DISCOVERY (1 = responde ao "Procurar na rede" do app, só UDP local), OMR_TOKEN (chave mestra opcional),
OMR_FAKE (1 = não roda o homr; só para testes).
Usa o homr (https://github.com/liebharc/homr, AGPL-3.0) para reconhecer as notas.
"""
import glob
import hashlib
import hmac
import json
import os
import queue
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from urllib.parse import urlparse

PORT = int(os.environ.get("PORT", "8080"))
BIND = os.environ.get("BIND", "0.0.0.0")
DATA = os.path.abspath(os.environ.get("DATA_DIR", "data"))
MASTER = os.environ.get("OMR_TOKEN", "")
FAKE = os.environ.get("OMR_FAKE") == "1"
DISCOVERY = os.environ.get("DISCOVERY", "0") == "1"
MAX_BYTES = 40 * 1024 * 1024
MAX_PAGES = 12
CODE_TTL = 600
FAIL_LIMIT = 5
LOCK_SECONDS = 900
ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # sem letras/números parecidos

os.makedirs(DATA, exist_ok=True)
DEV_FILE = os.path.join(DATA, "devices.json")
CODE_FILE = os.path.join(DATA, "pair_code.txt")
REQ_FILE = os.path.join(DATA, "new_code.request")
_lock = threading.Lock()


# ---------- dispositivos pareados ----------
def _hash(tok):
    return hashlib.sha256(tok.encode()).hexdigest()


def _load_devices():
    try:
        return json.load(open(DEV_FILE))
    except Exception:  # noqa: BLE001
        return {}


def _save_devices(d):
    tmp = DEV_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(d, f)
    try:
        os.chmod(tmp, 0o600)
    except Exception:  # noqa: BLE001
        pass
    os.replace(tmp, DEV_FILE)


def token_ok(tok):
    if not tok:
        return False
    if MASTER and hmac.compare_digest(tok, MASTER):
        return True
    h = _hash(tok)
    with _lock:
        devs = _load_devices()
    return any(hmac.compare_digest(h, k) for k in devs)


# ---------- código de pareamento ----------
_code = {"value": "", "until": 0.0}


def new_code(announce=True):
    raw = "".join(secrets.choice(ALPHABET) for _ in range(8))
    with _lock:
        _code["value"] = raw
        _code["until"] = time.time() + CODE_TTL
    shown = raw[:4] + "-" + raw[4:]
    try:
        with open(CODE_FILE, "w") as f:
            f.write(shown + "\n")
        os.chmod(CODE_FILE, 0o600)
    except Exception:  # noqa: BLE001
        pass
    if announce:
        print("\n  CÓDIGO DE PAREAMENTO: %s   (vale %d minutos, uso único)\n" % (shown, CODE_TTL // 60), flush=True)
    return shown


def code_ok(given):
    g = "".join(c for c in (given or "").upper() if c in ALPHABET)
    with _lock:
        good = _code["value"] and time.time() < _code["until"] and len(g) == 8 and hmac.compare_digest(g, _code["value"])
        if good:
            _code["value"] = ""
    return good


# ---------- bloqueio por endereço ----------
_fails = {}


def blocked(ip):
    with _lock:
        n, until = _fails.get(ip, (0, 0.0))
        return until > time.time()


def fail(ip):
    with _lock:
        n, until = _fails.get(ip, (0, 0.0))
        if until and until <= time.time():
            n = 0
        n += 1
        _fails[ip] = (n, time.time() + LOCK_SECONDS if n >= FAIL_LIMIT else 0.0)


def ok_login(ip):
    with _lock:
        _fails.pop(ip, None)


# ---------- reconhecimento ----------
def run(cmd, timeout):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


FAKE_XML = (b'<?xml version="1.0" encoding="utf-8"?><score-partwise version="3.1"><part-list><score-part id="P1"><part-name>Teste</part-name></score-part></part-list>'
            b'<part id="P1"><measure number="1"><attributes><divisions>1</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes>'
            b'<note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><type>whole</type></note></measure></part></score-partwise>')


def homr_image(path):
    if FAKE:
        out = os.path.splitext(path)[0] + ".musicxml"
        open(out, "wb").write(FAKE_XML)
        return out
    r = run(["homr", path], 900)
    out = os.path.splitext(path)[0] + ".musicxml"
    if not os.path.exists(out):
        raise RuntimeError("não encontrei notas na imagem: " + (r.stderr or r.stdout or "")[-300:])
    return out


def preprocess(path):
    """Melhora fotos antes de reconhecer (cinza, contraste, tamanho). Só roda se a biblioteca PIL existir."""
    try:
        from PIL import Image, ImageOps
    except Exception:  # noqa: BLE001
        return path
    try:
        im = Image.open(path)
        im = ImageOps.exif_transpose(im).convert("L")
        im = ImageOps.autocontrast(im, cutoff=1)
        w, h = im.size
        if max(w, h) > 3600:
            k = 3600 / max(w, h)
            im = im.resize((int(w * k), int(h * k)))
        out = os.path.splitext(path)[0] + "_p.png"
        im.save(out)
        return out
    except Exception:  # noqa: BLE001
        return path


def merge(files):
    """Junta os compassos de várias páginas, parte por parte."""
    base = ET.parse(files[0])
    parts = base.getroot().findall("part")
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
    buf = BytesIO()
    base.write(buf, encoding="utf-8", xml_declaration=True)
    return buf.getvalue()


def kind_of(data):
    if data[:4] == b"%PDF":
        return "pdf"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if data[:2] == b"\xff\xd8":
        return "jpg"
    raise ValueError("formato não suportado (use PDF, PNG ou JPEG)")


def recognize(data, progress=lambda **k: None):
    kind = kind_of(data)
    with tempfile.TemporaryDirectory() as tmp:
        if kind == "pdf":
            progress(stage="abrindo o PDF")
            pdf = os.path.join(tmp, "in.pdf")
            open(pdf, "wb").write(data)
            r = run(["pdftoppm", "-r", "200", "-png", pdf, os.path.join(tmp, "page")], 300)
            pages = sorted(glob.glob(os.path.join(tmp, "page*.png")))[:MAX_PAGES]
            if not pages:
                raise RuntimeError("não consegui abrir o PDF: " + (r.stderr or "")[-200:])
        else:
            img = os.path.join(tmp, "in." + kind)
            open(img, "wb").write(data)
            pages = [img]
        outs = []
        for i, p in enumerate(pages, 1):
            progress(stage="lendo as notas", page=i, pages=len(pages))
            outs.append(homr_image(preprocess(p)))
        progress(stage="juntando as páginas", page=len(pages), pages=len(pages))
        return merge(outs) if len(outs) > 1 else open(outs[0], "rb").read()


# ---------- fila de trabalhos ----------
JOBS = {}
Q = queue.Queue()


def worker():
    while True:
        jid, data = Q.get()
        job = JOBS.get(jid)
        if not job:
            continue
        job.update(status="running", stage="começando")
        try:
            job["result"] = recognize(data, lambda **k: job.update(k))
            job.update(status="done", stage="pronto")
        except ValueError as e:
            job.update(status="error", error=str(e), code=415)
        except Exception as e:  # noqa: BLE001
            job.update(status="error", error="falha na leitura: %s" % e, code=422)
        job["finished"] = time.time()


def cleaner():
    while True:
        time.sleep(300)
        now = time.time()
        for k in [k for k, j in JOBS.items() if j.get("finished") and now - j["finished"] > 3600]:
            JOBS.pop(k, None)


def watch_request():
    while True:
        time.sleep(3)
        if os.path.exists(REQ_FILE):
            try:
                os.remove(REQ_FILE)
            except Exception:  # noqa: BLE001
                pass
            new_code()


# ---------- HTTP ----------
class Handler(BaseHTTPRequestHandler):
    server_version = "Compasso"
    sys_version = ""

    def log_message(self, fmt, *a):  # sem IP/URL no log
        pass

    def _send(self, code, body, ctype="text/plain; charset=utf-8"):
        if isinstance(body, (dict, list)):
            body, ctype = json.dumps(body, ensure_ascii=False), "application/json; charset=utf-8"
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def _ip(self):
        return self.client_address[0]

    def _auth(self):
        ip = self._ip()
        h = self.headers.get("Authorization", "")
        if h.startswith("Bearer ") and token_ok(h[7:].strip()):
            ok_login(ip)  # chave válida passa sempre: ninguém na rede consegue trancar o dono para fora
            return True
        if blocked(ip):
            self._send(429, "muitas tentativas; tente de novo em alguns minutos")
            return False
        fail(ip)
        self._send(401, "chave inválida")
        return False

    def _body(self):
        try:
            n = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            n = 0
        if n <= 0 or n > MAX_BYTES:
            self._send(413, "arquivo vazio ou maior que 40 MB")
            return None
        return self.rfile.read(n)

    def do_GET(self):
        path = urlparse(self.path).path.rstrip("/")
        if path == "/health":
            return self._send(200, {"ok": True, "app": "compasso-omr"})
        if path == "/check":
            if self._auth():
                self._send(200, "ok")
            return
        if path.startswith("/jobs/"):
            if not self._auth():
                return
            parts = path.split("/")
            job = JOBS.get(parts[2]) if len(parts) > 2 else None
            if not job:
                return self._send(404, "trabalho não encontrado")
            if len(parts) > 3 and parts[3] == "result":
                if job["status"] != "done":
                    return self._send(409, "ainda não terminou")
                return self._send(200, job["result"], "application/vnd.recordare.musicxml+xml")
            pub = {k: v for k, v in job.items() if k in ("status", "stage", "page", "pages", "error")}
            return self._send(200, pub)
        self._send(404, "não encontrado")

    def do_POST(self):
        path = urlparse(self.path).path.rstrip("/")
        if path == "/pair":
            ip = self._ip()
            if blocked(ip):
                return self._send(429, "muitas tentativas; tente de novo em alguns minutos")
            body = self._body()
            if body is None:
                return
            try:
                req = json.loads(body)
            except Exception:  # noqa: BLE001
                return self._send(400, "pedido inválido")
            if not code_ok(str(req.get("code", ""))):
                fail(ip)
                return self._send(401, "código errado ou vencido")
            ok_login(ip)
            tok = secrets.token_urlsafe(32)
            with _lock:
                d = _load_devices()
                d[_hash(tok)] = {"name": str(req.get("device", ""))[:60], "created": int(time.time())}
                _save_devices(d)
            new_code(announce=False)
            print("  aparelho pareado: %s" % (str(req.get("device", ""))[:60] or "sem nome"), flush=True)
            return self._send(200, {"token": tok})
        if not self._auth():
            return
        if path == "/unpair":
            h = self.headers.get("Authorization", "")[7:].strip()
            with _lock:
                d = _load_devices()
                d.pop(_hash(h), None)
                _save_devices(d)
            return self._send(200, "ok")
        if path in ("/jobs", "/omr"):
            data = self._body()
            if data is None:
                return
            try:
                kind_of(data)
            except ValueError as e:
                return self._send(415, str(e))
            if path == "/jobs":
                jid = uuid.uuid4().hex
                JOBS[jid] = {"status": "queued", "stage": "na fila", "created": time.time()}
                Q.put((jid, data))
                return self._send(202, {"id": jid})
            try:
                with _lock_sync:
                    xml = recognize(data)
                return self._send(200, xml, "application/vnd.recordare.musicxml+xml")
            except Exception as e:  # noqa: BLE001
                return self._send(422, "falha na leitura: %s" % e)
        self._send(404, "não encontrado")


_lock_sync = threading.Lock()


def discovery():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", 45678))
    me = socket.gethostname()
    while True:
        data, addr = s.recvfrom(64)
        if data.strip() == b"COMPASSO?":
            s.sendto(json.dumps({"app": "compasso-omr", "port": PORT, "name": me}).encode(), addr)


def main():
    if "--new-code" in sys.argv:
        open(REQ_FILE, "w").close()
        for _ in range(10):
            time.sleep(1)
            if not os.path.exists(REQ_FILE):
                print(open(CODE_FILE).read().strip())
                return
        print("O servidor não respondeu: ele está rodando?")
        return
    if "--revoke-all" in sys.argv:
        _save_devices({})
        print("Todos os aparelhos foram desconectados.")
        return
    if "--list" in sys.argv:
        for v in _load_devices().values():
            print(time.strftime("%d/%m/%Y %H:%M", time.localtime(v["created"])), v.get("name") or "(sem nome)")
        return
    threading.Thread(target=worker, daemon=True).start()
    threading.Thread(target=cleaner, daemon=True).start()
    threading.Thread(target=watch_request, daemon=True).start()
    if DISCOVERY:
        threading.Thread(target=discovery, daemon=True).start()
    print("Compasso OMR no ar: porta %d, endereço %s%s" % (PORT, BIND, " (modo teste)" if FAKE else ""), flush=True)
    if not _load_devices() and not MASTER:
        print("Nenhum aparelho pareado ainda. Abra o app: Ajustes > Servidor privado > Parear.", flush=True)
    new_code()
    ThreadingHTTPServer((BIND, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
