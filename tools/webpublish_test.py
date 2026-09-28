#!/usr/bin/env python3
"""Integration test for the "Publish to web" feature (Forkgram Android).

Exercises the service contract the app uses:
  upload: POST {base}/upload, multipart fields "text" + one "files[]" per file
          (filename + content type), header X-Upload-Token
          -> {"id": "<id>", "url": "<page url>"}
  delete: DELETE <page url>, header X-Upload-Token

Flow: upload text + 1 jpg + 1 mp4, assert the page URL opens (GET 200)
and contains the text, then delete and assert GET 404/410.

Config via env (mirrors the app settings):
  WEBPUBLISH_BASE    e.g. https://drop.zik.one   (required)
  UPLOAD_TOKEN       upload token                (required)

Usage:
  WEBPUBLISH_BASE=https://drop.zik.one UPLOAD_TOKEN=<token> python3 tools/webpublish_test.py

Manual equivalent with curl (same contract):

  BASE=https://drop.zik.one ; T=<token>
  # 1. upload text + files
  curl -s -A 'Forkgram/1.0' -H "X-Upload-Token: $T" \
    -F 'text=Steps to reproduce: ...' \
    -F 'files[]=@screen.jpg;type=image/jpeg' \
    -F 'files[]=@rec.mp4;type=video/mp4' \
    "$BASE/upload" | tee /tmp/up.json
  LINK=$(python3 -c "import json;print(json.load(open('/tmp/up.json'))['url'])")
  # 2. verify
  curl -s "$LINK" | grep -o 'Steps to reproduce'
  # 3. delete
  curl -s -X DELETE -A 'Forkgram/1.0' -H "X-Upload-Token: $T" "$LINK"
  curl -s -o /dev/null -w '%{http_code}\n' "$LINK"   # expect 404 or 410
"""

import base64
import json
import os
import random
import string
import sys
import urllib.error
import urllib.request

USER_AGENT = "Forkgram/1.0"

# Minimal 1x1 JPEG (valid SOI..EOI), stdlib-only fixture.
TINY_JPG_B64 = (
    "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////"
    "//////////////////////////////////////////2wBDAf//////////////////////////////////////////"
    "//////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAA"
    "AAAAAP/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AK//Z"
)
# Minimal MP4: ftyp box (isom) + empty mdat. Opaque stores accept it as bytes.
TINY_MP4 = (
    b"\x00\x00\x00\x18ftypisom\x00\x00\x00\x01isomiso2"
    b"\x00\x00\x00\x08free"
    b"\x00\x00\x00\x10mdat\x00\x00\x00\x00\x00\x00\x00\x00"
)


def http(method, url, body=None, headers=None):
    req = urllib.request.Request(url, data=body, method=method,
                                 headers={"User-Agent": USER_AGENT, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def main():
    base = os.environ.get("WEBPUBLISH_BASE", "").strip().rstrip("/")
    assert base.startswith(("http://", "https://")), "set WEBPUBLISH_BASE, e.g. https://drop.zik.one"
    token = os.environ.get("UPLOAD_TOKEN", "")
    assert token, "set UPLOAD_TOKEN"
    print(f"base={base}")

    workdir = "/tmp/webpublish_test"
    os.makedirs(workdir, exist_ok=True)
    jpg = os.path.join(workdir, "screen.jpg")
    mp4 = os.path.join(workdir, "rec.mp4")
    with open(jpg, "wb") as f:
        f.write(base64.b64decode(TINY_JPG_B64))
    with open(mp4, "wb") as f:
        f.write(TINY_MP4)
    assert os.path.getsize(jpg) <= 5 * 1024 * 1024
    assert os.path.getsize(mp4) <= 20 * 1024 * 1024

    text = "test report: steps to reproduce — one, two"
    assert len(text.encode()) <= 5 * 1024 * 1024

    boundary = "----ForkgramTest" + "".join(random.choices(string.hexdigits, k=16))

    def field(name, value):
        return (f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
                f"{value}\r\n").encode()

    def file_field(path, filename, mime):
        with open(path, "rb") as f:
            payload = f.read()
        head = (f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="files[]"; filename="{filename}"\r\n'
                f"Content-Type: {mime}\r\n\r\n").encode()
        return head + payload + b"\r\n"

    body = (field("text", text)
            + file_field(jpg, "screen.jpg", "image/jpeg")
            + file_field(mp4, "rec.mp4", "video/mp4")
            + f"--{boundary}--\r\n".encode())
    headers = {"Content-Type": f"multipart/form-data; boundary={boundary}",
               "X-Upload-Token": token}
    code, raw, _ = http("POST", base + "/upload", body, headers)
    assert code in (200, 201), f"upload failed: HTTP {code}: {raw[:200]!r}"
    data = json.loads(raw.decode())
    link, rid = data.get("url"), data.get("id")
    assert link and rid, f"upload response has no url/id: {raw[:200]!r}"
    print("page:", link, "id:", rid)

    code, raw, _ = http("GET", link)
    assert code == 200, f"page GET failed: HTTP {code}"
    assert "steps to reproduce" in raw.decode(errors="replace"), "page does not contain the text"
    print("GET 200 + text OK")

    code, raw, _ = http("DELETE", link, None, {"X-Upload-Token": token})
    assert code in (200, 201, 204), f"DELETE failed: HTTP {code}: {raw[:200]!r}"
    code, _, _ = http("GET", link)
    assert code in (404, 410), f"page still alive after DELETE: HTTP {code}"
    print("DELETE OK, GET after delete:", code)
    print("ALL INTEGRATION CHECKS PASSED")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("FAIL:", e, file=sys.stderr)
        sys.exit(1)
