import argparse
from dataclasses import dataclass, field
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

FILES_CHANNEL = "frkgrmfiles"
FEED_CHANNEL = "frkgrmfeed_android"
VERSION = r"[0-9]+(?:\.[0-9]+){3}"
MAX_PREVIEW = 2 * 1024 * 1024
PREVIEW_DELAYS = (0, 2, 4, 8, 16, 20, 20)


class PublishError(Exception):
    pass


class PreviewUnavailable(PublishError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise PublishError("Telegram preview redirected; publication stopped")


@dataclass
class Post:
    id: int
    date_link: bool = False
    text: list[str] = field(default_factory=list)
    document: list[str] = field(default_factory=list)


class Preview(HTMLParser):
    def __init__(self, channel):
        super().__init__()
        self.channel = channel
        self.depth = 0
        self.post_depth = 0
        self.field_depth = 0
        self.capture = None
        self.post = None
        self.posts = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        classes = (attrs.get("class") or "").split()
        if tag == "div":
            self.depth += 1
            if "js-widget_message" in classes:
                match = re.fullmatch(rf"{self.channel}/([1-9][0-9]*)", attrs.get("data-post") or "")
                if not match or self.post is not None:
                    raise PublishError("Unexpected Telegram post layout")
                self.post = Post(int(match[1]))
                self.post_depth = self.depth
            elif self.post and self.capture is None:
                if "tgme_widget_message_text" in classes:
                    self.capture = self.post.text
                elif "tgme_widget_message_document_title" in classes:
                    self.capture = self.post.document
                if self.capture is not None:
                    self.field_depth = self.depth
        elif tag == "br" and self.capture is not None:
            self.capture.append("\n")
        elif tag == "a" and self.post and "tgme_widget_message_date" in classes:
            self.post.date_link = attrs.get("href") == f"https://t.me/{self.channel}/{self.post.id}"

    def handle_endtag(self, tag):
        if tag != "div":
            return
        if self.capture is not None and self.depth == self.field_depth:
            self.capture = None
        if self.post and self.depth == self.post_depth:
            self.posts.append(self.post)
            self.post = None
        self.depth -= 1

    def handle_data(self, data):
        if self.capture is not None:
            self.capture.append(data)

    def result(self):
        ids = [post.id for post in self.posts]
        if not ids or self.post is not None or ids != sorted(set(ids)) or not all(post.date_link for post in self.posts):
            raise PublishError(f"Cannot establish latest chronological post in @{self.channel}")
        return self.posts


def read_preview(channel):
    url = f"https://t.me/s/{channel}?_={secrets.token_hex(12)}"
    parser = Preview(channel)
    try:
        request = Request(url, headers={"Cache-Control": "no-cache, no-store, max-age=0"})
        with build_opener(NoRedirect).open(request, timeout=15) as response:
            if response.url != url or response.status != 200 or response.headers.get_content_type() != "text/html":
                raise PublishError(f"Unexpected response for @{channel}")
            dates = response.headers.get_all("Date") or []
            now = datetime.now(timezone.utc)
            if (len(dates) != 1 or abs((now - parsedate_to_datetime(dates[0])).total_seconds()) > 300
                    or response.headers.get("Age", "0") != "0"
                    or "no-store" not in response.headers.get("Cache-Control", "").lower().split(", ")):
                raise PublishError(f"Cannot establish freshness of @{channel}")
            payload = response.read(MAX_PREVIEW + 1)
            if len(payload) > MAX_PREVIEW:
                raise PublishError(f"Telegram preview exceeds size limit for @{channel}")
            parser.feed(payload.decode())
            parser.close()
    except HTTPError as error:
        if error.code not in (429, 500, 502, 503, 504):
            raise PublishError(f"Unexpected Telegram preview HTTP status for @{channel}") from None
        raise PreviewUnavailable(f"Telegram preview temporarily unavailable for @{channel}") from None
    except (URLError, TimeoutError, ConnectionError):
        raise PreviewUnavailable(f"Telegram preview temporarily unavailable for @{channel}") from None
    except (OSError, TypeError, ValueError):
        raise PublishError(f"Cannot safely read Telegram preview @{channel}") from None
    return parser.result()


def unique_dict(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise PublishError("Android feed contains duplicate JSON keys")
        result[key] = value
    return result


def parse_feed(text):
    try:
        state = json.loads(text, object_pairs_hook=unique_dict)
    except ValueError:
        raise PublishError("Latest Android feed post is not valid JSON; no older post substituted") from None
    if not isinstance(state, dict) or not isinstance(state.get("android"), dict):
        raise PublishError("Latest Android feed post lacks a whole Android manifest")
    android = state["android"]
    for key in ("beta", "release"):
        reference_version(android.get(key))
    if "optional" in android:
        reference_version(android["optional"])
    return state


def read_feed():
    latest = read_preview(FEED_CHANNEL)[-1]
    return latest.id, parse_feed("".join(latest.text))


def reference_version(value):
    match = re.fullmatch(rf"({VERSION}):{FILES_CHANNEL}#[1-9][0-9]*", str(value))
    if match is None:
        raise PublishError("Android feed contains an invalid APK reference")
    return tuple(map(int, match[1].split(".")))


def state_digest(state):
    return hashlib.sha256(json.dumps(state, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def api(token, method, args=()):
    command = [
        "curl", "--disable", "--silent", "--noproxy", "*", "--proto", "=http",
        "--config", "-", "--connect-timeout", "5", "--max-time", "930" if method == "sendDocument" else "20",
        "--request", "POST", *args,
    ]
    config = f'url = "http://127.0.0.1:18083/bot{token}/{method}"\n'
    try:
        response = subprocess.run(command, input=config, text=True, capture_output=True, check=True)
        result = json.loads(response.stdout)
        if not isinstance(result, dict) or result.get("ok") is not True:
            code = result.get("error_code") if isinstance(result, dict) else None
            suffix = f" (code {code})" if type(code) is int else ""
            raise PublishError(f"Bot API {method} failed{suffix}")
        return result["result"]
    except (OSError, subprocess.CalledProcessError, KeyError, ValueError):
        raise PublishError(f"Bot API {method} did not return a usable response") from None


def form_fields(**values):
    return [arg for key, value in values.items() for arg in ("--data-urlencode", f"{key}={value}")]


def checked_channel(value, username):
    if (not isinstance(value, dict) or value.get("type") != "channel"
            or value.get("username", "").lower() != username or type(value.get("id")) is not int
            or value["id"] >= 0):
        raise PublishError(f"Unexpected Bot API channel for @{username}")
    return value["id"]


def validate_message(message, username, chat_id):
    if not isinstance(message, dict) or checked_channel(message.get("chat"), username) != chat_id:
        raise PublishError("Bot API returned a message from the wrong channel")
    post_id = message.get("message_id")
    if type(post_id) is not int or post_id <= 0:
        raise PublishError("Bot API returned an invalid channel message ID")
    return post_id


def check_bot(token, before):
    bot_id = api(token, "getMe")["id"]
    channels = {}
    for channel in (FILES_CHANNEL, FEED_CHANNEL):
        chat = api(token, "getChat", form_fields(chat_id=f"@{channel}"))
        channel_id = checked_channel(chat, channel)
        member = api(token, "getChatMember", form_fields(chat_id=channel_id, user_id=bot_id))
        if member.get("user", {}).get("id") != bot_id or not (
            member.get("status") == "creator" or
            (member.get("status") == "administrator" and member.get("can_post_messages") is True)
        ):
            raise PublishError(f"The bot cannot post to @{channel}")
        channels[channel] = channel_id
        pinned = chat.get("pinned_message")
        if channel == FEED_CHANNEL and pinned:
            pinned_id = validate_message(pinned, channel, channel_id)
            if pinned_id >= before[0] and (pinned_id, parse_feed(pinned.get("text") or "")) != before:
                raise PublishError("Bot API's pinned feed disagrees with the latest public preview")
    return channels


def check_snapshot(feed, files, args):
    if args.expected_feed_id is not None and (
        (args.expected_feed_id, args.expected_feed_sha256, args.expected_files_id) !=
        (feed[0], state_digest(feed[1]), files[-1].id)
    ):
        raise PublishError("Feed or files channel changed since the approved preflight; no writes performed")


def wait_for_document(post_id, file_name):
    for delay in PREVIEW_DELAYS:
        if delay:
            time.sleep(delay)
        try:
            posts = read_preview(FILES_CHANNEL)
        except PreviewUnavailable:
            continue
        for post in posts:
            if post.id == post_id:
                if "".join(post.document).strip() != file_name:
                    raise PublishError("Published document does not match the exact public APK pointer")
                return
    raise PublishError("The APK post has not appeared in the public files preview")


def wait_for_feed(post_id, state):
    for delay in PREVIEW_DELAYS:
        if delay:
            time.sleep(delay)
        try:
            observed_id, observed_state = read_feed()
        except PreviewUnavailable:
            continue
        if observed_id == post_id and observed_state == state:
            return
        if observed_id >= post_id:
            raise PublishError("Published manifest is no longer the latest exact feed post")
    raise PublishError("The new manifest has not appeared in the public feed preview")


def publish(args):
    version = args.version
    if not re.fullmatch(VERSION, version):
        raise PublishError("Invalid Android APK version")
    apk = Path(f"ForkClient {version}.apk")
    if not apk.is_file() or not apk.stat().st_size:
        raise PublishError("Signed APK is missing")
    if not args.preflight and os.environ.get("GITHUB_RUN_ATTEMPT", "1") != "1":
        raise PublishError("No automatic publication retry. Reconcile the exact prior APK and feed IDs before dispatching again")

    before = read_feed()
    files = read_preview(FILES_CHANNEL)
    check_snapshot(before, files, args)
    android = before[1]["android"]
    current_version = tuple(map(int, version.split(".")))
    prior = reference_version(android["optional"]) if "optional" in android else None
    if prior == current_version:
        existing_id = int(android["optional"].rsplit("#", 1)[-1])
        wait_for_document(existing_id, apk.name)
        print(f"Android {version} is already in the optional feed at @{FILES_CHANNEL}/{existing_id}")
        return
    stable_versions = [reference_version(android[key]) for key in ("beta", "release")]
    if (prior is not None and current_version < prior) or current_version <= max(stable_versions):
        raise PublishError("The candidate must exceed the existing stable and optional versions")
    copies = [post.id for post in files if "".join(post.document).strip() == apk.name]
    if args.reconcile_apk_id is not None:
        if copies != [args.reconcile_apk_id]:
            raise PublishError("Files preview does not uniquely resolve the verified earlier APK upload")
    elif copies:
        raise PublishError("An APK with this filename is already visible but not in the feed; reconcile the earlier upload")

    token = os.environ.get("ANDROID_BOT_TOKEN", "")
    if not re.fullmatch(r"[1-9][0-9]*:[A-Za-z0-9_-]+", token):
        raise PublishError("Missing or invalid ANDROID_BOT_TOKEN")
    channels = check_bot(token, before)
    print(f"Read-only checks passed: feed={before[0]} sha256={state_digest(before[1])} files={files[-1].id}", flush=True)
    if args.preflight:
        return

    if read_feed() != before:
        raise PublishError("The latest Android manifest changed before the APK upload; no writes performed")
    files_now = read_preview(FILES_CHANNEL)
    check_snapshot(before, files_now, args)
    if files_now[-1].id != files[-1].id:
        raise PublishError("The files channel changed before publication; no writes performed")
    if [post.id for post in files_now if "".join(post.document).strip() == apk.name] != copies:
        raise PublishError("The candidate filename changed in the files channel; no writes performed")
    if args.reconcile_apk_id is not None:
        post_id = args.reconcile_apk_id
        print(f"Reusing verified APK post https://t.me/{FILES_CHANNEL}/{post_id}; no document upload performed", flush=True)
    else:
        changelog = os.environ.get("FORK_CHANGELOG", "").strip() or "— Optional update."
        caption = f"Fork Client Android.\n\n{version} Changelog:\n{changelog}"
        print("APK upload starting once. If interrupted, reconcile the files channel before a new dispatch.", flush=True)
        sent = api(token, "sendDocument", [
            "--form-string", f"chat_id={channels[FILES_CHANNEL]}",
            "--form-string", f"caption={caption}",
            "--form", f"document=@{apk};type=application/vnd.android.package-archive",
        ])
        post_id = validate_message(sent, FILES_CHANNEL, channels[FILES_CHANNEL])
        print(f"APK post: https://t.me/{FILES_CHANNEL}/{post_id}; keep this ID for reconciliation", flush=True)
        document = sent.get("document") or {}
        if (post_id <= files[-1].id or document.get("file_name") != apk.name
                or document.get("file_size") != apk.stat().st_size or not document.get("file_id")):
            raise PublishError("Bot API APK details mismatch; feed was not changed")
        if os.environ.get("GITHUB_EVENT_NAME") == "push":
            with apk.open("rb") as signed:
                apk_digest = hashlib.file_digest(signed, "sha256").hexdigest()
            print(f"ANDROID_OPTIONAL_RECEIPT {version} {apk_digest} {post_id} {before[0]} {state_digest(before[1])}", flush=True)
        wait_for_document(post_id, apk.name)

    if read_feed() != before:
        raise PublishError("The latest Android manifest changed during upload; stop with the APK post ID, do not retry")
    state = {**before[1], "android": {**android, "optional": f"{version}:{FILES_CHANNEL}#{post_id}"}}
    message = json.dumps(state, separators=(",", ":"))
    print("Feed send starting once. If interrupted, reconcile the feed before a new dispatch.", flush=True)
    feed_response = api(token, "sendMessage", form_fields(chat_id=channels[FEED_CHANNEL], text=message, link_preview_options='{"is_disabled":true}'))
    feed_id = validate_message(feed_response, FEED_CHANNEL, channels[FEED_CHANNEL])
    print(f"Feed post: https://t.me/{FEED_CHANNEL}/{feed_id}", flush=True)
    if feed_id <= before[0] or feed_response.get("text") != message:
        raise PublishError("Bot API feed receipt did not match the exact manifest; reconcile before a retry")
    wait_for_feed(feed_id, state)
    print(f"Android {version} optional feed verified at @{FEED_CHANNEL}/{feed_id}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument("--preflight", action="store_true")
    parser.add_argument("--expected-feed-id", type=int)
    parser.add_argument("--expected-feed-sha256")
    parser.add_argument("--expected-files-id", type=int)
    parser.add_argument("--reconcile-apk-id", type=int)
    args = parser.parse_args()
    specified = (args.expected_feed_id, args.expected_feed_sha256, args.expected_files_id)
    if any(x is not None for x in specified) and (
        not all(specified) or not re.fullmatch("[a-f0-9]{64}", args.expected_feed_sha256)
        or min(args.expected_feed_id, args.expected_files_id) < 1
    ):
        parser.error("Supply both expected post IDs plus the exact manifest SHA-256")
    if args.reconcile_apk_id is not None and (
        args.reconcile_apk_id < 1 or not all(specified)
    ):
        parser.error("Feed-only reconciliation requires a verified post ID and the approved full snapshot")
    try:
        publish(args)
    except PublishError as error:
        raise SystemExit(str(error)) from None


if __name__ == "__main__":
    main()
