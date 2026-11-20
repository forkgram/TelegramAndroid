import argparse
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import publish_android_optional as publisher

import read_android_optional_receipt as recovery

class RecoveryReceiptTest(unittest.TestCase):
    def setUp(self):
        self.line = f"Publish optional APK through Bot API\t2026-11-25T15:15:23Z ANDROID_OPTIONAL_RECEIPT 13.2.6.0 {'a' * 64} 1013 132 {'b' * 64}\n"

    def test_recovers_exact_ids_and_signed_apk_digest(self):
        self.assertEqual(recovery.read_receipt(self.line), dict(
            version="13.2.6.0", apk_sha256="a" * 64, apk_id=1013,
            feed_id=132, feed_sha256="b" * 64,
        ))

    def test_rejects_ambiguous_unknown_or_feed_write_receipts(self):
        for data in ("", self.line * 2, self.line.replace(" 1013 ", " 0 "),
                     self.line + "Feed send starting once"):
            with self.subTest(data=data), self.assertRaises(ValueError):
                recovery.read_receipt(data)



class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        previous = Path.cwd()
        self.addCleanup(os.chdir, previous)
        os.chdir(self.folder.name)
        self.apk = Path("ForkClient 13.2.5.0.apk")
        self.apk.write_bytes(b"fixture APK")
        self.state = {
            "android": {
                "beta": "13.2.3.0:frkgrmfiles#990",
                "release": "13.2.3.0:frkgrmfiles#990",
                "optional": "13.2.4.0:frkgrmfiles#1003",
                "future": {"keep": True},
            },
            "other": ["keep", "entire state"],
        }
        self.files = [publisher.Post(id=1011, date_link=True, document=["desktop fixture"])]
        self.args = argparse.Namespace(
            version="13.2.5.0", preflight=False, reconcile_apk_id=None, expected_feed_id=131,
            expected_feed_sha256=publisher.state_digest(self.state), expected_files_id=1011,
        )
        self.files_id = -10001
        self.feed_id = -10002
        self.before = (131, self.state)

    def post(self, post_id, text, channel=publisher.FEED_CHANNEL):
        return (
            f'<div class="js-widget_message" data-post="{channel}/{post_id}">'
            f'<div class="tgme_widget_message_text">{text}</div>'
            f'<a class="tgme_widget_message_date" href="https://t.me/{channel}/{post_id}"><time></time></a>'
            '</div>'
        )

    def test_preview_uses_exact_latest_post_even_if_invalid(self):
        parser = publisher.Preview(publisher.FEED_CHANNEL)
        parser.feed(self.post(130, json.dumps(self.state)) + self.post(131, "not the manifest"))
        with patch.object(publisher, "read_preview", return_value=parser.result()):
            with self.assertRaisesRegex(publisher.PublishError, "no older post substituted"):
                publisher.read_feed()

    def test_preview_rejects_ambiguous_chronology_and_channel(self):
        parser = publisher.Preview(publisher.FEED_CHANNEL)
        parser.feed(self.post(131, "{}") + self.post(130, "{}"))
        with self.assertRaises(publisher.PublishError):
            parser.result()
        parser = publisher.Preview(publisher.FEED_CHANNEL)
        with self.assertRaises(publisher.PublishError):
            parser.feed(self.post(132, "{}", publisher.FILES_CHANNEL))

    def test_manifest_rejects_duplicate_keys(self):
        duplicate = '{"android":{"beta":"a","beta":"b","release":"c"}}'
        with self.assertRaisesRegex(publisher.PublishError, "duplicate"):
            publisher.parse_feed(duplicate)

    def exercise(self, before, api):
        with (
            patch.object(publisher, "read_feed", side_effect=before),
            patch.object(publisher, "read_preview", return_value=self.files),
            patch.object(publisher, "check_bot", return_value={
                publisher.FILES_CHANNEL: self.files_id, publisher.FEED_CHANNEL: self.feed_id,
            }),
            patch.object(publisher, "api", side_effect=api) as bot,
            patch.object(publisher, "wait_for_document") as document,
            patch.object(publisher, "wait_for_feed") as feed,
            patch.dict(os.environ, {"ANDROID_BOT_TOKEN": "123:fake", "GITHUB_RUN_ATTEMPT": "1"}),
        ):
            publisher.publish(self.args)
        return bot, document, feed

    def receipt(self, channel, msg_id, **extra):
        return {"chat": {"username": channel, "type": "channel",
                         "id": self.files_id if channel == publisher.FILES_CHANNEL else self.feed_id},
                "message_id": msg_id, **extra}

    def send(self, token, method, args):
        if method == "sendDocument":
            return self.receipt(publisher.FILES_CHANNEL, 1037, document={
                "file_size": self.apk.stat().st_size, "file_name": self.apk.name, "file_id": "fixture"})
        text = next(arg[5:] for arg in args if arg.startswith("text="))
        return self.receipt(publisher.FEED_CHANNEL, 132, text=text)

    def test_preflight_never_writes(self):
        self.args.preflight = True
        bot, document, feed = self.exercise([self.before], self.send)
        bot.assert_not_called()
        document.assert_not_called()
        feed.assert_not_called()

    def test_preserves_full_manifest_and_exact_returned_pointer(self):
        bot, document, feed = self.exercise([self.before] * 3, self.send)
        self.assertEqual([item.args[1] for item in bot.call_args_list], ["sendDocument", "sendMessage"])
        document.assert_called_once_with(1037, self.apk.name)
        expected = {**self.state, "android": {**self.state["android"],
                                            "optional": "13.2.5.0:frkgrmfiles#1037"}}
        feed.assert_called_once_with(132, expected)
        self.assertEqual(self.state["android"]["optional"], "13.2.4.0:frkgrmfiles#1003")

    def test_detects_a_concurrent_feed_writer_after_upload(self):
        sent = []
        def api(*args):
            sent.append(args[1])
            return self.send(*args)
        with self.assertRaisesRegex(publisher.PublishError, "changed during upload"):
            self.exercise([self.before, self.before, (132, self.state)], api)
        self.assertEqual(sent, ["sendDocument"])

    def test_rejects_bad_bot_api_receipt_without_changing_feed(self):
        sent = []
        def api(*args):
            sent.append(args[1])
            return self.receipt(publisher.FEED_CHANNEL, 1037)
        with self.assertRaisesRegex(publisher.PublishError, "Unexpected Bot API channel"):
            self.exercise([self.before] * 3, api)
        self.assertEqual(sent, ["sendDocument"])

    def test_optional_push_survives_stale_and_transient_cdn_after_exactly_one_document(self):
        self.args.expected_feed_id = self.args.expected_feed_sha256 = self.args.expected_files_id = None
        visible = publisher.Post(id=1037, date_link=True, document=[self.apk.name])
        expected = {**self.state, "android": {**self.state["android"],
                                            "optional": "13.2.5.0:frkgrmfiles#1037"}}
        files = [self.files, self.files]
        files += [self.files, publisher.PreviewUnavailable("timeout"), self.files, self.files + [visible]]
        feeds = [self.before] * 3 + [self.before, publisher.PreviewUnavailable("HTTP 503"), (132, expected)]
        with (
            patch.object(publisher, "read_feed", side_effect=feeds) as feed,
            patch.object(publisher, "read_preview", side_effect=files) as preview,
            patch.object(publisher, "check_bot", return_value={
                publisher.FILES_CHANNEL: self.files_id, publisher.FEED_CHANNEL: self.feed_id,
            }),
            patch.object(publisher, "api", side_effect=self.send) as bot,
            patch.object(publisher.time, "sleep"),
            patch.dict(os.environ, {"ANDROID_BOT_TOKEN": "123:fake", "GITHUB_RUN_ATTEMPT": "1",
                                    "GITHUB_EVENT_NAME": "push"}),
            patch("builtins.print") as logs,
        ):
            publisher.publish(self.args)
        self.assertEqual([item.args[1] for item in bot.call_args_list], ["sendDocument", "sendMessage"])
        sent_payload = next(arg[5:] for arg in bot.call_args_list[1].args[2] if arg.startswith("text="))
        self.assertEqual(json.loads(sent_payload), expected)
        self.assertEqual((feed.call_count, preview.call_count), (6, 6))
        upload_line = next(c.args[0] for c in logs.call_args_list if c.args[0].startswith("ANDROID_OPTIONAL_RECEIPT "))
        self.assertEqual(recovery.read_receipt(upload_line), dict(
            version="13.2.5.0", apk_sha256=publisher.hashlib.sha256(self.apk.read_bytes()).hexdigest(),
            apk_id=1037, feed_id=131, feed_sha256=publisher.state_digest(self.state),
        ))

    def test_document_preview_waits_for_slow_cdn_without_writing(self):
        visible = publisher.Post(id=1012, date_link=True, document=[self.apk.name])
        reads = [publisher.PreviewUnavailable("timeout")] + [self.files] * (len(publisher.PREVIEW_DELAYS) - 2) + [self.files + [visible]]
        with (
            patch.object(publisher, "read_preview", side_effect=reads) as preview,
            patch.object(publisher.time, "sleep") as delay,
            patch.object(publisher, "api") as bot,
        ):
            publisher.wait_for_document(1012, self.apk.name)
        self.assertEqual(preview.call_count, len(publisher.PREVIEW_DELAYS))
        self.assertEqual(sum(x.args[0] for x in delay.call_args_list), 70)
        bot.assert_not_called()

    def test_feed_preview_waits_for_exact_delayed_receipt(self):
        changed = {**self.state, "android": {**self.state["android"],
                                           "optional": "13.2.5.0:frkgrmfiles#1012"}}
        reads = [self.before] * (len(publisher.PREVIEW_DELAYS) - 1) + [(132, changed)]
        with patch.object(publisher, "read_feed", side_effect=reads) as reader, patch.object(publisher.time, "sleep"):
            publisher.wait_for_feed(132, changed)
        self.assertEqual(reader.call_count, len(publisher.PREVIEW_DELAYS))

    def test_reconcile_sends_only_feed_with_verified_post_id(self):
        apk_digest = publisher.hashlib.sha256(self.apk.read_bytes()).hexdigest()
        manifest_digest = publisher.state_digest(self.state)
        prior = recovery.read_receipt(f"ANDROID_OPTIONAL_RECEIPT 13.2.5.0 {apk_digest} 1012 131 {manifest_digest}")
        self.args.version = prior["version"]
        self.args.reconcile_apk_id = prior["apk_id"]
        self.args.expected_files_id = prior["apk_id"]
        self.args.expected_feed_id = prior["feed_id"]
        self.args.expected_feed_sha256 = prior["feed_sha256"]
        self.files.append(publisher.Post(id=1012, date_link=True, document=[self.apk.name]))
        bot, document, feed = self.exercise([self.before] * 3, self.send)
        self.assertEqual([item.args[1] for item in bot.call_args_list], ["sendMessage"])
        document.assert_not_called()
        expected = {**self.state, "android": {**self.state["android"],
                                            "optional": "13.2.5.0:frkgrmfiles#1012"}}
        feed.assert_called_once_with(132, expected)

    def test_reconcile_rejects_ambiguous_or_wrong_post(self):
        self.args.reconcile_apk_id = 1012
        self.files[-1].document = [self.apk.name]
        with self.assertRaisesRegex(publisher.PublishError, "uniquely resolve"):
            self.exercise([self.before], self.send)

    def test_duplicate_visible_apk_stops_before_upload(self):
        self.files[-1].document = [self.apk.name]
        with self.assertRaisesRegex(publisher.PublishError, "already visible"):
            self.exercise([self.before], self.send)

    def test_manual_preflight_digest_detects_same_id_edit(self):
        self.args.expected_feed_sha256 = "0" * 64
        with self.assertRaisesRegex(publisher.PublishError, "changed since"):
            self.exercise([self.before], self.send)

    def test_actions_retry_never_reposts(self):
        with patch.dict(os.environ, {"GITHUB_RUN_ATTEMPT": "2"}):
            with patch.object(publisher, "read_feed") as feed:
                with self.assertRaisesRegex(publisher.PublishError, "No automatic"):
                    publisher.publish(self.args)
                feed.assert_not_called()


if __name__ == "__main__":
    unittest.main()
