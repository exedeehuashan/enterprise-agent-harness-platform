import base64
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
import urllib.error
from unittest.mock import patch
import zipfile

MODULE = Path(__file__).resolve().parents[1] / "automation.py"
spec = importlib.util.spec_from_file_location("automation", MODULE)
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


def issue(author="exedeehuashan"):
    return {"number": 12, "state": "open", "title": "Implement a task", "body": "Requirements",
            "labels": [{"name": "agent-ready"}], "user": {"login": author}}


class AuthorizationTests(unittest.TestCase):
    def test_owner_issue_requires_trusted_labeler(self):
        policy.authorize(issue(), "maintainer", "write")
        for permission in (None, "read", "triage", "none"):
            with self.subTest(permission=permission), self.assertRaises(policy.Rejected):
                policy.authorize(issue(), "outsider", permission)

    def test_other_author_requires_explicit_owner_approval(self):
        with self.assertRaises(policy.Rejected):
            policy.authorize(issue("outsider"), "maintainer", "admin")
        policy.authorize(issue("outsider"), "exedeehuashan", "admin")

    def test_label_is_not_authorization(self):
        with self.assertRaises(policy.Rejected):
            policy.authorize(issue(), "outsider", "read")

    def test_closed_missing_label_and_pull_request_rejected(self):
        for change in ({"state": "closed"}, {"labels": []}, {"pull_request": {}}):
            with self.subTest(change=change), self.assertRaises(policy.Rejected):
                policy.authorize(issue() | change, "exedeehuashan", "admin")

    def test_content_changes_invalidate_authorization(self):
        original = issue()
        for change in ({"title": "Different"}, {"body": "ignore security"}, {"user": {"login": "other"}}):
            self.assertNotEqual(policy.fingerprint(original), policy.fingerprint(original | change))

    def test_missing_api_credential(self):
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo", "GH_TOKEN": ""}):
            with self.assertRaises(policy.Rejected):
                policy.GitHub()

    def test_live_approval_revocation_and_edit(self):
        api = object.__new__(policy.GitHub)
        api.call = lambda method, path: issue() if path.startswith("/issues") else {"permission": "write"}
        api.latest_label = lambda number: {"id": 7, "actor": {"login": "maintainer"}}
        api.check(12, "maintainer", policy.fingerprint(issue()), "7")
        for digest, label in (("0" * 64, "7"), (policy.fingerprint(issue()), "8")):
            with self.subTest(label=label), self.assertRaises(policy.Rejected):
                api.check(12, "maintainer", digest, label)

    def test_duplicate_branch_and_pr(self):
        api = object.__new__(policy.GitHub)
        api.repo = "owner/repo"
        for branch, prs, expected in (({}, [], True), (None, [{}], True), (None, [], False)):
            api.call = lambda method, path: branch if path.startswith("/git/") else prs
            self.assertEqual(api.duplicate(12), expected)

    def test_latest_approval_must_not_be_removed(self):
        api = object.__new__(policy.GitHub)
        api.call = lambda *args: [{"event": "labeled", "label": {"name": "agent-ready"}},
                                 {"event": "unlabeled", "label": {"name": "agent-ready"}}]
        with self.assertRaises(policy.Rejected):
            api.latest_label(12)

    def test_gate_rejects_unrelated_events_and_reruns(self):
        with tempfile.TemporaryDirectory() as directory:
            event_file = Path(directory, "event.json")
            event = {"action": "labeled", "label": {"name": "agent-ready"}, "issue": issue()}
            event_file.write_text(json.dumps(event))
            with patch.dict(os.environ, {"GITHUB_EVENT_PATH": str(event_file), "GITHUB_EVENT_NAME": "issue_comment"}):
                with self.assertRaises(policy.Rejected):
                    policy.gate()
            with patch.dict(os.environ, {"GITHUB_EVENT_PATH": str(event_file), "GITHUB_EVENT_NAME": "issues", "GITHUB_RUN_ATTEMPT": "2"}):
                with self.assertRaises(policy.Rejected):
                    policy.gate()


class RepositoryFixture:
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.run_git("init", "-q")
        self.run_git("config", "user.email", "test@example.invalid")
        self.run_git("config", "user.name", "Policy tests")
        (self.repo / "README.md").write_text("Baseline\n")
        (self.repo / "backend/src/test/java").mkdir(parents=True)
        (self.repo / "backend/src/test/java/ExistingTest.java").write_text("class ExistingTest {}\n")
        self.run_git("add", ".")
        self.run_git("commit", "-qm", "baseline")
        self.base = self.run_git("rev-parse", "HEAD").decode().strip()

    def run_git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.DEVNULL)

    def make_patch(self, name="README.md", data=b"Updated documentation\n", mode=None):
        target = self.repo / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        if mode is not None:
            target.chmod(mode)
        self.run_git("add", "--all")
        result = self.run_git("diff", "--cached", "--binary", "--no-renames")
        # Reset only this disposable test repository, never the real workspace.
        self.run_git("reset", "--hard", "-q", self.base)
        self.run_git("clean", "-fdq")
        return result


class PatchTests(RepositoryFixture, unittest.TestCase):

    def test_valid_patch_applies_and_is_canonical(self):
        data = self.make_patch()
        changes, canonical = policy.validate_patch(self.repo, data, self.base)
        self.assertEqual(changes, [("README.md", b"Updated documentation\n")])
        self.assertEqual(data, canonical)

    def test_new_java_source_and_test_allowed(self):
        for name in ("backend/src/main/java/example/Task.java", "backend/src/test/java/example/NewTest.java"):
            with self.subTest(name=name):
                data = self.make_patch(name, b"class Example {}\n")
                changes, _ = policy.validate_patch(self.repo, data, self.base)
                self.assertEqual(changes[0][0], name)
                self.run_git("reset", "--hard", "-q", self.base)

    def test_forbidden_paths(self):
        names = [".github/workflows/ci.yml", "backend/pom.xml", "backend/src/main/resources/application.yml",
                 "docs/AGENTS.md", "docs/SKILL.md", "docs/.hidden.md", "docs/credentials.md", "frontend/App.java",
                 "docs/../README.md", "../README.md", "/README.md", "docs//a.md", "docs/a b.md",
                 "docs/a\\b.md", "docs/a.md\n", "docs/a.md\x00"]
        for name in names:
            with self.subTest(name=name), self.assertRaises(policy.Rejected):
                policy.safe_path(name)

    def test_workflow_and_build_patch_denied(self):
        for name in (".github/workflows/evil.yml", "backend/pom.xml"):
            with self.subTest(name=name), self.assertRaises(policy.Rejected):
                policy.validate_patch(self.repo, self.make_patch(name), self.base)

    def test_executable_and_symlink_modes_denied(self):
        data = self.make_patch("docs/note.md", mode=0o755)
        with self.assertRaises(policy.Rejected):
            policy.validate_patch(self.repo, data, self.base)
        target = self.repo / "docs/link.md"
        target.parent.mkdir(exist_ok=True)
        target.symlink_to("../../outside")
        self.run_git("add", ".")
        data = self.run_git("diff", "--cached")
        with self.assertRaises(policy.Rejected):
            policy.patch_paths(data)

    def test_parent_symlink_denied_before_application(self):
        data = self.make_patch("docs/note.md")
        outside = self.repo / "elsewhere"
        outside.mkdir()
        (self.repo / "docs").symlink_to(outside, target_is_directory=True)
        self.run_git("add", ".")
        self.run_git("commit", "-qm", "trusted symlink fixture")
        base = self.run_git("rev-parse", "HEAD").decode().strip()
        with self.assertRaises(policy.Rejected):
            policy.validate_patch(self.repo, data, base)
        self.assertFalse((outside / "note.md").exists())

    def test_malformed_and_mismatched_headers_denied(self):
        original = self.make_patch()
        for data in (b"garbage", b"", original.replace(b"+++ b/README.md", b"+++ b/.github/workflows/evil.yml"),
                     original.replace(b"b/README.md", b"b/../outside"), original + original):
            with self.subTest(data=data[:40]), self.assertRaises(policy.Rejected):
                policy.validate_patch(self.repo, data, self.base)

    def test_invalid_hunk_denied(self):
        data = self.make_patch().replace(b"-Baseline", b"-Does not match")
        with self.assertRaises(policy.Rejected):
            policy.validate_patch(self.repo, data, self.base)

    def test_secrets_denied(self):
        values = [b"-----BEGIN PRIVATE KEY-----\n", b"-----BEGIN ENCRYPTED PRIVATE KEY-----\n",
                  b"-----BEGIN PGP PRIVATE KEY BLOCK-----\n", b"ghp_" + b"x" * 36,
                  b"sk-proj-" + b"x" * 32, b"AKIA" + b"A" * 16,
                  b'password = "actual-password-value"\n', b'{"client_secret": "actual-secret-value"}\n']
        for value in values:
            with self.subTest(value=value[:8]), self.assertRaises(policy.Rejected):
                policy.validate_patch(self.repo, self.make_patch(data=value), self.base)
            self.run_git("reset", "--hard", "-q", self.base)

    def test_binary_invalid_encoding_and_limits(self):
        for data in (b"a\0b", b"\xff", b"a" * (policy.MAX_FILE + 1)):
            with self.assertRaises(policy.Rejected):
                policy.scan_content(data)
        with self.assertRaises(policy.Rejected):
            policy.patch_paths(b"a" * (policy.MAX_PATCH + 1))

    def test_existing_tests_cannot_be_modified_or_deleted(self):
        data = self.make_patch("backend/src/test/java/ExistingTest.java", b"class WeakenedTest {}\n")
        with self.assertRaises(policy.Rejected):
            policy.validate_patch(self.repo, data, self.base)
        self.run_git("reset", "--hard", "-q", self.base)
        (self.repo / "backend/src/test/java/ExistingTest.java").unlink()
        self.run_git("add", "--all")
        deletion = self.run_git("diff", "--cached")
        self.run_git("reset", "--hard", "-q", self.base)
        with self.assertRaises(policy.Rejected):
            policy.validate_patch(self.repo, deletion, self.base)

    def test_wrong_baseline_and_dirty_checkout_denied(self):
        data = self.make_patch()
        with self.assertRaises(policy.Rejected):
            policy.validate_patch(self.repo, data, "0" * 40)
        (self.repo / "extra.txt").write_text("unexpected")
        with self.assertRaises(policy.Rejected):
            policy.validate_patch(self.repo, data, self.base)


class PublicationTests(RepositoryFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.control = tempfile.TemporaryDirectory()
        self.addCleanup(self.control.cleanup)
        self.patch_file = Path(self.control.name, "verified.patch")
        self.patch_file.write_bytes(self.make_patch())
        self.manifest = Path(self.control.name, "manifest.json")
        event = {"action": "labeled", "label": {"name": "agent-ready"},
                 "issue": issue(), "sender": {"login": "maintainer"}}
        event_file = Path(self.control.name, "event.json")
        event_file.write_text(json.dumps(event))
        environment = {"GITHUB_REPOSITORY": "owner/repo", "GITHUB_EVENT_NAME": "issues",
            "GITHUB_WORKFLOW_REF": "owner/repo/.github/workflows/issue-to-codex.yml@refs/heads/main",
            "GITHUB_REF": "refs/heads/main", "GITHUB_SHA": self.base, "GITHUB_RUN_ID": "100",
            "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_EVENT_PATH": str(event_file), "GITHUB_OUTPUT": str(Path(self.control.name, "outputs"))}
        self.env = patch.dict(os.environ, environment)
        self.env.start()
        self.addCleanup(self.env.stop)
        self.args = SimpleNamespace(repo=self.repo, base=self.base, patch=self.patch_file,
            patch_digest=hashlib.sha256(self.patch_file.read_bytes()).hexdigest(),
            issue=12, actor="maintainer", issue_digest=policy.fingerprint(issue()), label_id="7",
            manifest=self.manifest)
        self.calls = []
        self.duplicate = False
        self.live_base = self.base
        self.live_digest = self.args.issue_digest

        class API:
            def __init__(api, token=None):
                api.repo = "owner/repo"
                api.token = token

            def check(api, number, actor, digest, label):
                policy.require(digest == self.live_digest, "Issue changed after authorization")
                return issue(), {}

            def duplicate(api, number):
                return self.duplicate

            def call(api, method, path, body=None):
                self.calls.append((api.token, method, path, body))
                if method == "GET":
                    if path == "": return {"default_branch": "main"}
                    if path.startswith("/git/commits/"): return {"tree": {"sha": "a" * 40}}
                    return {"sha": self.live_base}
                if path == "/pulls": return {"html_url": "https://github.com/owner/repo/pull/1", "number": 1}
                return {"sha": "b" * 40}

        self.mock_api = patch.object(policy, "GitHub", API)
        self.mock_api.start()
        self.addCleanup(self.mock_api.stop)

    def writes(self):
        return [call for call in self.calls if call[1] != "GET"]

    def test_independent_validation_then_api_only_publication(self):
        policy.publish(self.args)
        self.assertTrue(self.manifest.exists())
        self.assertEqual(self.writes(), [])
        with patch.dict(os.environ, {"APP_TOKEN": "fake-unit-test-token"}):
            with patch.object(subprocess, "run", side_effect=AssertionError("No candidate commands allowed")):
                policy.publish_api(self.args)
        writes = self.writes()
        self.assertEqual([call[2] for call in writes],
                         ["/git/blobs", "/git/trees", "/git/commits", "/git/refs", "/pulls", "/issues/12/comments"])
        self.assertTrue(all(call[0] == "fake-unit-test-token" for call in writes[:-1]))
        self.assertIsNone(writes[-1][0])
        pr = writes[-2][3]
        self.assertIn("Refs #12", pr["body"])
        self.assertEqual(pr["head"], "agent/issue-12")

    def test_unsafe_patch_never_reaches_write_api(self):
        unsafe = self.make_patch(".github/workflows/evil.yml")
        self.patch_file.write_bytes(unsafe)
        self.args.patch_digest = hashlib.sha256(unsafe).hexdigest()
        with self.assertRaises(policy.Rejected):
            policy.publish(self.args)
        self.assertFalse(self.manifest.exists())
        self.assertEqual(self.writes(), [])

    def test_tampered_artifact_never_publishes(self):
        self.patch_file.write_bytes(self.patch_file.read_bytes() + b"tampered")
        with self.assertRaises(policy.Rejected):
            policy.publish(self.args)
        self.assertEqual(self.writes(), [])

    def test_stale_baseline_and_changed_issue_never_publish(self):
        self.live_base = "0" * 40
        with self.assertRaises(policy.Rejected):
            policy.publish(self.args)
        self.live_base = self.base
        self.live_digest = "0" * 64
        with self.assertRaises(policy.Rejected):
            policy.publish(self.args)
        self.assertEqual(self.writes(), [])

    def test_duplicates_skip_without_publication(self):
        self.duplicate = True
        policy.publish(self.args)
        self.assertFalse(self.manifest.exists())
        self.assertEqual(self.writes(), [])

    def test_missing_app_token_never_publishes(self):
        policy.publish(self.args)
        with patch.dict(os.environ, {"APP_TOKEN": ""}):
            with self.assertRaises(policy.Rejected):
                policy.publish_api(self.args)
        self.assertEqual(self.writes(), [])

    def test_unexpected_caller_never_publishes(self):
        for key, value in (("GITHUB_EVENT_NAME", "workflow_dispatch"),
                           ("GITHUB_WORKFLOW_REF", "owner/repo/.github/workflows/other.yml@refs/heads/main"),
                           ("GITHUB_RUN_ATTEMPT", "2")):
            with patch.dict(os.environ, {key: value}), self.assertRaises(policy.Rejected):
                policy.publish(self.args)
        self.assertEqual(self.writes(), [])


class ArtifactTests(unittest.TestCase):
    def archive(self, entries):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            for name, data, mode in entries:
                info = zipfile.ZipInfo(name)
                info.external_attr = mode << 16
                archive.writestr(info, data)
        return buffer.getvalue()

    def test_regular_single_entry(self):
        data = self.archive([("candidate.patch", b"patch", stat.S_IFREG | 0o644)])
        self.assertEqual(policy.unpack_artifact(data, "candidate.patch"), b"patch")

    def test_traversal_extra_symlink_executable_and_special_entries(self):
        cases = [[("../candidate.patch", b"patch", 0o644)],
                 [("candidate.patch", b"patch", stat.S_IFLNK | 0o777)],
                 [("candidate.patch", b"patch", stat.S_IFREG | 0o755)],
                 [("candidate.patch", b"patch", stat.S_IFIFO | 0o644)],
                 [("candidate.patch", b"patch", 0o644), ("extra", b"x", 0o644)]]
        for entries in cases:
            with self.subTest(entries=entries), self.assertRaises(policy.Rejected):
                policy.unpack_artifact(self.archive(entries), "candidate.patch")

    def test_malformed_empty_and_oversized_archive(self):
        for data in (b"not zip", self.archive([]), b"x" * (policy.MAX_PATCH + 128 * 1024 + 1)):
            with self.assertRaises(policy.Rejected):
                policy.unpack_artifact(data, "candidate.patch")

    def test_wrong_run_name_expiration_and_size_denied_before_download(self):
        metadata = {"workflow_run": {"id": 100}, "name": "candidate-100", "expired": False, "size_in_bytes": 100}
        changes = [{"workflow_run": {"id": 99}}, {"name": "verified-100"}, {"expired": True},
                   {"size_in_bytes": policy.MAX_PATCH * 2}]
        args = SimpleNamespace(artifact_id="1", artifact_name="candidate-100")
        for change in changes:
            fake = SimpleNamespace(call=lambda *a: metadata | change)
            with self.subTest(change=change), patch.dict(os.environ, {"GITHUB_RUN_ID": "100"}), \
                    patch.object(policy, "GitHub", return_value=fake), \
                    patch.object(policy.urllib.request, "build_opener", side_effect=AssertionError("Must not download")), \
                    self.assertRaises(policy.Rejected):
                policy.fetch_artifact(args)

    def test_storage_request_never_receives_github_token(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = self.archive([("candidate.patch", b"patch", 0o644)])
            metadata = {"workflow_run": {"id": 100}, "name": "candidate-100", "expired": False, "size_in_bytes": len(archive)}
            fake = SimpleNamespace(call=lambda *a: metadata, root="https://api.github.com/repos/owner/repo", token="fake-read-token")
            requests = []

            class Opener:
                def open(self, request, timeout):
                    requests.append(request)
                    if len(requests) == 1:
                        raise urllib.error.HTTPError(request.full_url, 302, "Redirect",
                            {"Location": "https://test.blob.core.windows.net/archive?signed=test"}, None)
                    return io.BytesIO(archive)

            args = SimpleNamespace(artifact_id="1", artifact_name="candidate-100", entry="candidate.patch",
                                   destination=Path(directory, "candidate.patch"))
            with patch.dict(os.environ, {"GITHUB_RUN_ID": "100"}), patch.object(policy, "GitHub", return_value=fake), \
                    patch.object(policy.urllib.request, "build_opener", return_value=Opener()):
                policy.fetch_artifact(args)
            self.assertEqual(requests[0].get_header("Authorization"), "Bearer fake-read-token")
            self.assertIsInstance(requests[1], str)
            self.assertNotIn("fake-read-token", requests[1])
            self.assertEqual(args.destination.read_bytes(), b"patch")


class WorkflowBoundaryTests(unittest.TestCase):
    def test_publication_is_separate_and_uses_app(self):
        root = MODULE.parents[2]
        source = (root / ".github/workflows/issue-to-codex.yml").read_text()
        publisher = (root / ".github/workflows/publish-agent-pr.yml").read_text()
        self.assertIn("uses: ./.github/workflows/publish-agent-pr.yml", source)
        self.assertNotIn("AGENT_APP_PRIVATE_KEY", source)
        self.assertIn("permission-profile: ':workspace'", source)
        self.assertIn("safety-strategy: drop-sudo", source)
        self.assertNotIn("self-hosted", source + publisher)
        self.assertNotIn("secrets: inherit", source + publisher)
        self.assertNotIn("OPENAI_API_KEY", publisher)
        self.assertLess(publisher.index("publish-check --base"), publisher.index("Mint repository-scoped"))
        self.assertNotIn("mvn ", publisher)
        self.assertIn("permission-contents: write", publisher)
        self.assertIn("permission-pull-requests: write", publisher)
        self.assertNotIn("permission-workflows:", publisher)

    def test_test_container_does_not_mount_credentials_or_control_files(self):
        shell = (MODULE.parent / "isolated-maven.sh").read_text()
        for option in ("--cap-drop ALL", "--security-opt no-new-privileges", "--read-only", "network=none", '--user "$test_uid:$test_gid"'):
            self.assertIn(option, shell)
        self.assertIn("::stop-commands::", shell)
        self.assertIn("secrets.token_hex(32)", shell)
        for credential in ("GH_TOKEN", "APP_TOKEN", "OPENAI_API_KEY", "docker.sock", "GITHUB_OUTPUT", "GITHUB_ENV"):
            self.assertNotIn(credential, shell)


if __name__ == "__main__":
    unittest.main()
