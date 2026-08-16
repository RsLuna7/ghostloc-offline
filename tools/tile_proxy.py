from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen


LISTEN_HOST = "127.0.0.1"
LISTEN_PORT = 18765
UPSTREAM_TEMPLATE = "https://a.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png"


class TileProxyHandler(BaseHTTPRequestHandler):
    def do_HEAD(self):
        self._serve(head_only=True)

    def do_GET(self):
        self._serve(head_only=False)

    def _serve(self, head_only: bool):
        parsed = urlparse(self.path)
        parts = parsed.path.strip("/").split("/")
        if parsed.path in ("/gaode-road", "/world-tile", "/world-tile-v3"):
            query = parse_qs(parsed.query)
            z = query.get("z", [None])[0]
            x = query.get("x", [None])[0]
            y = query.get("y", [None])[0]
        elif len(parts) == 4 and parts[0] in ("gaode-road", "world-tile", "world-tile-v3"):
            z = parts[1]
            x = parts[2]
            y_part = parts[3]
            y = y_part[:-4] if y_part.endswith(".png") else None
        elif len(parts) == 3:
            z = parts[0]
            x = parts[1]
            y_part = parts[2]
            y = y_part[:-4] if y_part.endswith(".png") else None
        else:
            print(f"{self.command} {self.path} -> not found", flush=True)
            self.send_error(404, "not found")
            return

        if not x or not y or not z:
            self.send_error(400, "missing x/y/z")
            return

        request = Request(
            UPSTREAM_TEMPLATE.format(z=z, x=x, y=y),
            headers={"User-Agent": "ghostloc-offline-tile-proxy/1.0"},
        )
        try:
            with urlopen(request, timeout=20) as response:
                payload = response.read()
                print(f"{self.command} {self.path} -> {len(payload)} bytes", flush=True)
                self.send_response(200)
                self.send_header("Content-Type", response.headers.get_content_type())
                self.send_header("Content-Length", str(len(payload)))
                self.send_header("Cache-Control", "public, max-age=3600")
                self.end_headers()
                if not head_only:
                    self.wfile.write(payload)
        except Exception as exc:
            print(f"{self.command} {self.path} -> upstream error: {exc}", flush=True)
            self.send_error(502, f"upstream error: {exc}")

    def log_message(self, fmt, *args):
        return


if __name__ == "__main__":
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), TileProxyHandler)
    print(f"tile proxy listening on http://{LISTEN_HOST}:{LISTEN_PORT}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
