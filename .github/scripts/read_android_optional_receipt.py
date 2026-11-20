#!/usr/bin/env python3
"""Extract one exact receipt from a verified optional workflow's failed job."""
import json
from pathlib import Path
import re
import sys

RECEIPT = re.compile(
    r"(?:^|\s)ANDROID_OPTIONAL_RECEIPT (\d+(?:\.\d+){3}) ([a-f0-9]{64}) "
    r"([1-9][0-9]*) ([1-9][0-9]*) ([a-f0-9]{64})[ \t\r]*$", re.MULTILINE | re.ASCII
)


def read_receipt(log):
    if "Feed send starting once" in log:
        raise ValueError("Prior run may have written the feed; independently reconcile it before another send")
    matches = RECEIPT.findall(log)
    if len(matches) != 1:
        raise ValueError("No unique validated Android optional upload receipt")
    version, apk_sha256, apk_id, feed_id, feed_sha256 = matches[0]
    return dict(version=version, apk_sha256=apk_sha256, apk_id=int(apk_id),
                feed_id=int(feed_id), feed_sha256=feed_sha256)


if __name__ == "__main__":
    try:
        receipt = read_receipt(Path(sys.argv[1]).read_text())
    except ValueError as error:
        raise SystemExit(str(error)) from None
    Path(sys.argv[2]).write_text(json.dumps(receipt))
    print(receipt["version"])
