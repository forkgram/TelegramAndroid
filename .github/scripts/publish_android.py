import argparse
import io
import json
import os
from datetime import datetime, timedelta
from pathlib import Path

import requests
from pyrogram import Client
from pyrogram.types import InputMediaDocument

PUBLIC_CHANNEL = "forkgram"
FILES_CHANNEL = "frkgrmfiles"
FEED_CHANNEL = "frkgrmfeed_android"
THUMBNAIL_URL = "https://zik.one/logo_android.jpg"


def download_thumbnail():
    try:
        response = requests.get(THUMBNAIL_URL, timeout=15)
        response.raise_for_status()
        thumbnail = Path("logo_android.jpg")
        thumbnail.write_bytes(response.content)
        return str(thumbnail)
    except (requests.RequestException, OSError):
        print("Thumbnail unavailable. Continuing without it.")
        return None


def update_scheduled_release(app, apk, caption, thumbnail):
    scheduled = app.get_scheduled_messages(PUBLIC_CHANNEL)
    if not scheduled:
        reply_to = next((message.id for message in app.get_chat_history(PUBLIC_CHANNEL)
                         if (message.caption or "").startswith("Fork Client Android.")), None)
        placeholder = io.BytesIO(b"a")
        placeholder.name = "empty.txt"
        app.send_document(
            chat_id=PUBLIC_CHANNEL,
            document=placeholder,
            disable_notification=True,
            reply_to_message_id=reply_to,
            schedule_date=datetime.now() + timedelta(days=350),
        )
        scheduled = app.get_scheduled_messages(PUBLIC_CHANNEL)
    if not scheduled:
        raise RuntimeError("Telegram did not return the scheduled Android release")
    app.edit_message_media(
        chat_id=PUBLIC_CHANNEL,
        message_id=scheduled[0].id,
        media=InputMediaDocument(media=str(apk), thumb=thumbnail, caption=caption),
        schedule_date=scheduled[0].date,
    )


def read_feed(app):
    for message in app.get_chat_history(FEED_CHANNEL, limit=20):
        if message.text:
            try:
                entry = json.loads(message.text)
            except ValueError:
                continue
            if isinstance(entry, dict) and isinstance(entry.get("android"), dict):
                return entry["android"]
    return {}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("optional", "release"))
    parser.add_argument("version")
    args = parser.parse_args()
    apk = Path(f"ForkClient {args.version}.apk")
    if not apk.is_file():
        parser.error(f"missing {apk}")
    changelog = os.environ.get("FORK_CHANGELOG", "").strip()
    if not changelog:
        official_version = args.version.rsplit(".", 1)[0]
        changelog = ("— Optional update." if args.mode == "optional"
                     else f"— Updated to {official_version} build of the official Telegram.")
    caption = f"Fork Client Android.\n\n`{args.version} Changelog:`\n{changelog}"
    thumbnail = download_thumbnail()
    with Client("android-release", api_id=int(os.environ["ANDROID_TELEGRAM_API_ID"]),
                api_hash=os.environ["ANDROID_TELEGRAM_API_HASH"]) as app:
        if args.mode == "release":
            update_scheduled_release(app, apk, caption, thumbnail)
        message = app.send_document(FILES_CHANNEL, str(apk), thumb=thumbnail, caption=caption)
        apk_ref = f"{args.version}:{FILES_CHANNEL}#{message.id}"
        android = read_feed(app)
        if args.mode == "optional":
            android["optional"] = apk_ref
        else:
            android.update(beta=apk_ref, release=apk_ref)
            android.pop("optional", None)
        app.send_message(FEED_CHANNEL, json.dumps({"android": android}, separators=(",", ":")))


if __name__ == "__main__":
    main()
