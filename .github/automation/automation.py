#!/usr/bin/env python3
"""Trusted issue automation controller. Standard library only; never load candidate code."""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

OWNER = "exedeehuashan"
LABEL = "agent-ready"
MAX_PATCH = 2 * 1024 * 1024
MAX_FILE = 256 * 1024
MAX_FILES = 50
SHA = re.compile(r"[0-9a-f]{40}")
PATH = re.compile(r"[A-Za-z0-9_./-]+")
SECRET_PATTERNS = [
    re.compile(rb"-----BEGIN [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----"),
    re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"),
    re.compile(rb"\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}"),
    re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    re.compile(rb"(?i)\b(?:password|api[_-]?key|client[_-]?secret|access[_-]?token)[\"']?\s*[:=]\s*[\"'][^\"'\s]{8,}[\"']"),
]


class Rejected(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Rejected(message)


def safe_path(value):
    require(isinstance(value, str) and PATH.fullmatch(value), "Noncanonical patch path")
    path = PurePosixPath(value)
    require(not path.is_absolute() and str(path) == value, "Noncanonical patch path")
    require(all(part not in (".", "..") and not part.startswith(".") for part in path.parts),
            "Hidden or traversing path")
    require(path.name not in ("AGENTS.md", "SKILL.md"), "Agent policy is protected")
    allowed = value == "README.md" or (
        value.startswith("docs/") and value.endswith(".md")) or (
        value.startswith(("backend/src/main/java/", "backend/src/test/java/"))
        and value.endswith(".java"))
    require(allowed, "Path outside the change allowlist")
    require(not re.search(r"(?i)(secret|credential|private[-_]?key|password|id_rsa|(?:^|/)\.env)", value),
            "Sensitive filename")
    return value


def scan_content(data):
    require(len(data) <= MAX_FILE, "File size limit exceeded")
    require(b"\0" not in data, "Binary content denied")
    try:
        data.decode("utf-8")
    except UnicodeDecodeError as ex:
        raise Rejected("Non-UTF-8 content denied") from ex
    require(not any(pattern.search(data) for pattern in SECRET_PATTERNS),
            "Potential credential detected (content redacted)")


def unpack_artifact(data, expected):
    require(len(data) <= MAX_PATCH + 128 * 1024, "Artifact archive too large")
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            require(len(entries) == 1 and entries[0].filename == expected, "Unexpected artifact entries")
            entry = entries[0]
            mode = entry.external_attr >> 16
            require(stat.S_IFMT(mode) in (0, stat.S_IFREG) and not mode & 0o111, "Unsafe artifact file mode")
            require(0 < entry.file_size <= MAX_PATCH and not entry.flag_bits & 1, "Unsafe artifact size or encryption")
            return archive.read(entry)
    except (zipfile.BadZipFile, RuntimeError) as ex:
        raise Rejected("Invalid artifact ZIP") from ex


def patch_paths(data):
    require(0 < len(data) <= MAX_PATCH, "Empty or oversized patch")
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeDecodeError as ex:
        raise Rejected("Non-UTF-8 patch") from ex
    paths = []
    current = None
    for line in lines:
        if line.startswith("diff --git "):
            match = re.fullmatch(r"diff --git a/([A-Za-z0-9_./-]+) b/([A-Za-z0-9_./-]+)", line)
            require(match is not None and match[1] == match[2], "Renames or ambiguous patch headers denied")
            current = safe_path(match[1])
            require(current not in paths, "Duplicate patch entry")
            paths.append(current)
        elif line.startswith(("--- ", "+++ ")):
            require(current is not None, "Missing diff header")
            expected = ("a/" if line.startswith("--- ") else "b/") + current
            require(line[4:] in (expected, "/dev/null"), "Mismatched patch path")
        elif line.startswith(("new file mode ", "deleted file mode ")):
            require(line.rsplit(" ", 1)[-1] == "100644", "Nonregular file mode denied")
        elif line.startswith(("old mode ", "new mode ", "rename ", "copy ",
                              "similarity index ", "dissimilarity index ", "GIT binary patch", "Binary files ")):
            raise Rejected("Modes, renames, copies, and binary changes denied")
        elif line.startswith("index "):
            require(re.fullmatch(r"index [0-9a-f]+\.\.[0-9a-f]+(?: 100644)?", line),
                    "Invalid index or file mode")
    require(0 < len(paths) <= MAX_FILES, "Patch file count limit exceeded")
    return paths


def git(repo, *args, input_data=None):
    result = subprocess.run(
        ["git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false",
         "-c", "core.attributesFile=/dev/null", "-C", str(repo), *args],
        input=input_data, capture_output=True, check=False)
    require(result.returncode == 0, "Git operation failed: " + args[0])
    return result.stdout


def validate_patch(repo, patch, base):
    """Run only on a fresh trusted checkout, never the worker's .git directory."""
    require(SHA.fullmatch(base), "Invalid baseline SHA")
    require(git(repo, "rev-parse", "HEAD").decode().strip() == base, "Baseline mismatch")
    require(not git(repo, "status", "--porcelain", "--untracked-files=all"), "Checkout must be clean")
    paths = patch_paths(patch)
    for name in paths:
        node = Path(repo)
        for part in PurePosixPath(name).parts:
            node = node / part
            require(not node.is_symlink(), "Symlink path denied")
            if node.exists():
                mode = node.stat().st_mode
                require(stat.S_ISDIR(mode) or (stat.S_ISREG(mode) and not mode & 0o111),
                        "Special or executable file denied")
    git(repo, "apply", "--check", "--index", "--whitespace=error-all", "-", input_data=patch)
    git(repo, "apply", "--index", "--whitespace=error-all", "-", input_data=patch)
    actual = git(repo, "diff", "--cached", "--name-only", "-z").decode().split("\0")[:-1]
    require(set(actual) == set(paths), "Patch path discrepancy")
    changes = []
    for name in actual:
        safe_path(name)
        target = Path(repo) / name
        if target.exists():
            require(target.is_file() and not target.is_symlink(), "Nonregular candidate file")
            require(not target.stat().st_mode & 0o111, "Executable candidate file")
            entry = git(repo, "ls-files", "--stage", "--", name).decode()
            require(entry.startswith("100644 "), "Nonregular index entry")
            data = target.read_bytes()
            scan_content(data)
            if name.startswith("backend/src/test/java/"):
                baseline = subprocess.run(["git", "-C", str(repo), "show", base + ":" + name],
                                          capture_output=True, check=False)
                require(baseline.returncode != 0 or baseline.stdout == data,
                        "Existing independent tests are protected; add new tests instead")
            changes.append((name, data))
        else:
            require(not name.startswith("backend/src/test/java/"), "Independent tests cannot be deleted")
            changes.append((name, None))
    # Never trust an agent-authored diff's claimed content or identity.
    canonical = git(repo, "diff", "--cached", "--no-ext-diff", "--no-textconv", "--no-renames", "--binary")
    return changes, canonical


def fingerprint(issue):
    data = {key: issue[key] for key in ("number", "title", "body")}
    data["author"] = issue["user"]["login"]
    return hashlib.sha256(json.dumps(data, sort_keys=True, ensure_ascii=True).encode()).hexdigest()


def authorize(issue, actor, permission):
    require("pull_request" not in issue and issue.get("state") == "open", "Not an open Issue")
    require(any(label.get("name") == LABEL for label in issue.get("labels", [])), "Required label missing")
    require(permission in ("admin", "maintain", "write"), "Labeler lacks trusted repository permission")
    require(issue.get("user", {}).get("login") == OWNER or actor == OWNER,
            "Issue author requires explicit owner approval")
    require(isinstance(issue.get("number"), int) and issue["number"] > 0, "Invalid Issue number")
    require(len(issue.get("title", "")) <= 1024 and len(issue.get("body") or "") <= 65536,
            "Issue input too large")


class GitHub:
    def __init__(self, token=None):
        self.token = token or os.environ.get("GH_TOKEN", "")
        require(bool(self.token), "Missing GitHub API credential")
        self.repo = os.environ["GITHUB_REPOSITORY"]
        self.root = "https://api.github.com/repos/" + self.repo

    def call(self, method, path, body=None):
        request = urllib.request.Request(self.root + path,
            data=None if body is None else json.dumps(body).encode(), method=method,
            headers={"Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as ex:
            if ex.code == 404 and method == "GET":
                return None
            raise Rejected("GitHub API request failed with HTTP " + str(ex.code)) from ex

    def latest_label(self, number):
        events = []
        for page in range(1, 21):
            batch = self.call("GET", f"/issues/{number}/events?per_page=100&page={page}")
            require(isinstance(batch, list), "Unable to read Issue approval events")
            events.extend(batch)
            if len(batch) < 100:
                break
        else:
            raise Rejected("Issue event history exceeds safe pagination limit")
        labels = [event for event in events if event.get("event") in ("labeled", "unlabeled")
                  and event.get("label", {}).get("name") == LABEL]
        require(bool(labels) and labels[-1]["event"] == "labeled", "No active label approval event")
        return labels[-1]

    def check(self, number, actor, digest=None, label_id=None):
        issue = self.call("GET", f"/issues/{number}")
        require(isinstance(issue, dict), "Issue missing")
        result = self.call("GET", "/collaborators/" + urllib.parse.quote(actor, safe="") + "/permission")
        authorize(issue, actor, (result or {}).get("permission"))
        approval = self.latest_label(number)
        require(approval.get("actor", {}).get("login") == actor, "Approval actor mismatch")
        if label_id is not None:
            require(str(approval["id"]) == str(label_id), "Approval was replaced or revoked")
        if digest is not None:
            require(fingerprint(issue) == digest, "Issue changed after authorization")
        return issue, approval

    def duplicate(self, number):
        branch = f"agent/issue-{number}"
        if self.call("GET", "/git/ref/heads/" + branch) is not None:
            return True
        owner = self.repo.split("/")[0]
        return bool(self.call("GET", "/pulls?state=all&head=" + urllib.parse.quote(owner + ":" + branch)))


def output(**values):
    destination = os.environ.get("GITHUB_OUTPUT")
    require(destination is not None, "Missing Actions output channel")
    with open(destination, "a", encoding="utf-8") as stream:
        for key, value in values.items():
            require(re.fullmatch(r"[A-Za-z0-9_./:-]+", str(value)), "Unsafe workflow output")
            stream.write(f"{key}={value}\n")


def gate():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    require(os.environ.get("GITHUB_EVENT_NAME") == "issues" and event.get("action") == "labeled"
            and event.get("label", {}).get("name") == LABEL, "Only agent-ready Issue labeling is supported")
    require("pull_request" not in event.get("issue", {}), "Pull requests are not Issues")
    require(os.environ.get("GITHUB_RUN_ATTEMPT") == "1", "Relabel to retry; workflow reruns are denied")
    actor = event["sender"]["login"]
    number = event["issue"]["number"]
    api = GitHub()
    issue, approval = api.check(number, actor)
    require(fingerprint(issue) == fingerprint(event["issue"]), "Issue changed since labeling")
    repository = api.call("GET", "")
    default = repository["default_branch"]
    require(os.environ["GITHUB_REF"] == "refs/heads/" + default, "Default branch required")
    base = api.call("GET", "/commits/" + urllib.parse.quote(default, safe=""))["sha"]
    require(base == os.environ["GITHUB_SHA"], "Default branch advanced; relabel to retry")
    if api.duplicate(number):
        print("Skipped: Issue already has an agent branch or PR")
        output(ready="false")
        return
    output(ready="true", issue=number, actor=actor, digest=fingerprint(issue),
           label_id=approval["id"], base=base)
    Path(os.environ["RUNNER_TEMP"], "requirements.json").write_text(json.dumps({
        "number": number, "title": issue["title"], "body": issue.get("body")}, ensure_ascii=True))


def collect(repo, base, destination):
    require(SHA.fullmatch(base), "Invalid baseline SHA")
    # Collection is explicitly untrusted; all policy decisions happen on fresh runners.
    git(repo, "add", "--intent-to-add", "--all")
    patch = git(repo, "diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--binary", base, "--")
    require(0 < len(patch) <= MAX_PATCH, "No changes or artifact too large")
    Path(destination).write_bytes(patch)


def verify(repo, patch_file, base, destination):
    _, canonical = validate_patch(repo, Path(patch_file).read_bytes(), base)
    Path(destination).write_bytes(canonical)
    output(digest=hashlib.sha256(canonical).hexdigest())


def fetch_artifact(args):
    require(args.artifact_id and args.artifact_id.isdigit(), "Invalid artifact ID")
    api = GitHub()
    metadata = api.call("GET", "/actions/artifacts/" + args.artifact_id)
    require(metadata and not metadata.get("expired") and metadata.get("workflow_run", {}).get("id")
            == int(os.environ["GITHUB_RUN_ID"]), "Artifact is not from this workflow run")
    require(metadata.get("name") == args.artifact_name, "Unexpected artifact name")
    require(metadata.get("size_in_bytes", MAX_PATCH * 2) <= MAX_PATCH + 128 * 1024, "Artifact too large")

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    request = urllib.request.Request(api.root + "/actions/artifacts/" + args.artifact_id + "/zip",
        headers={"Authorization": "Bearer " + api.token, "Accept": "application/vnd.github+json"})
    try:
        urllib.request.build_opener(NoRedirect).open(request, timeout=30)
        raise Rejected("Expected signed artifact download redirect")
    except urllib.error.HTTPError as ex:
        require(ex.code == 302, "Artifact download authorization failed")
        location = ex.headers.get("Location", "")
    url = urllib.parse.urlparse(location)
    require(url.scheme == "https" and url.hostname and not url.username and
            url.hostname.endswith((".blob.core.windows.net", ".actions.githubusercontent.com", ".githubusercontent.com")),
            "Unexpected artifact storage endpoint")
    # Never forward GitHub Authorization headers to artifact storage, or follow further redirects.
    with urllib.request.build_opener(NoRedirect).open(location, timeout=30) as response:
        data = response.read(MAX_PATCH + 128 * 1024 + 1)
    Path(args.destination).write_bytes(unpack_artifact(data, args.entry))


def publish(args):
    require(os.environ.get("GITHUB_EVENT_NAME") == "issues", "Publisher requires an Issue caller")
    require(os.environ.get("GITHUB_RUN_ATTEMPT") == "1", "Relabel to retry; publisher reruns are denied")
    expected = os.environ["GITHUB_REPOSITORY"] + "/.github/workflows/issue-to-codex.yml@refs/heads/"
    require(os.environ.get("GITHUB_WORKFLOW_REF", "").startswith(expected), "Unexpected publisher caller")
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    require(event.get("action") == "labeled" and event.get("label", {}).get("name") == LABEL
            and event.get("issue", {}).get("number") == args.issue
            and event.get("sender", {}).get("login") == args.actor, "Publisher event mismatch")
    read = GitHub()
    issue, _ = read.check(args.issue, args.actor, args.issue_digest, args.label_id)
    require(fingerprint(event["issue"]) == args.issue_digest, "Original authorization mismatch")
    repository = read.call("GET", "")
    default = repository["default_branch"]
    require(os.environ["GITHUB_REF"] == "refs/heads/" + default, "Publisher must run from default branch")
    current = read.call("GET", "/commits/" + urllib.parse.quote(default, safe=""))["sha"]
    require(current == args.base == os.environ["GITHUB_SHA"], "Stale baseline; relabel to retry")
    if read.duplicate(args.issue):
        print("Skipped: Issue already has an agent branch or PR")
        output(ready="false")
        return
    patch = Path(args.patch).read_bytes()
    require(hashlib.sha256(patch).hexdigest() == args.patch_digest, "Tested patch digest mismatch")
    changes, canonical = validate_patch(args.repo, patch, args.base)
    require(canonical == patch, "Only canonical verified patches may publish")
    manifest = [{"path": name, "content": None if data is None else base64.b64encode(data).decode()}
                for name, data in changes]
    Path(args.manifest).write_text(json.dumps({"base": args.base, "issue": args.issue,
        "default": default, "changes": manifest, "digest": args.patch_digest}))
    output(ready="true")


def publish_api(args):
    """Called only AFTER validation and token minting. No candidate command execution."""
    manifest = json.loads(Path(args.manifest).read_text())
    read = GitHub()
    read.check(args.issue, args.actor, args.issue_digest, args.label_id)
    current = read.call("GET", "/commits/" + urllib.parse.quote(manifest["default"], safe=""))["sha"]
    require(current == args.base == manifest["base"] and manifest["issue"] == args.issue,
            "Baseline changed before publication")
    if read.duplicate(args.issue):
        print("Skipped: concurrent publication already exists")
        return
    require(bool(os.environ.get("APP_TOKEN")), "Missing publisher App token")
    write = GitHub(os.environ["APP_TOKEN"])
    tree = []
    for change in manifest["changes"]:
        name = safe_path(change["path"])
        sha = None
        if change["content"] is not None:
            data = base64.b64decode(change["content"], validate=True)
            scan_content(data)
            sha = write.call("POST", "/git/blobs", {"content": change["content"], "encoding": "base64"})["sha"]
        tree.append({"path": name, "mode": "100644", "type": "blob", "sha": sha})
    base_commit = read.call("GET", "/git/commits/" + args.base)
    new_tree = write.call("POST", "/git/trees", {"base_tree": base_commit["tree"]["sha"], "tree": tree})["sha"]
    commit = write.call("POST", "/git/commits", {"message": f"Implement approved Issue #{args.issue}",
                      "tree": new_tree, "parents": [args.base]})["sha"]
    # Blob uploads can take time: recheck approval immediately before exposing a branch.
    read.check(args.issue, args.actor, args.issue_digest, args.label_id)
    current = read.call("GET", "/commits/" + urllib.parse.quote(manifest["default"], safe=""))["sha"]
    require(current == args.base, "Baseline changed during publication preparation")
    if read.duplicate(args.issue):
        print("Skipped: concurrent publication already exists")
        return
    branch = f"agent/issue-{args.issue}"
    write.call("POST", "/git/refs", {"ref": "refs/heads/" + branch, "sha": commit})
    run_url = "https://github.com/" + write.repo + "/actions/runs/" + os.environ["GITHUB_RUN_ID"]
    changed = "\n".join("- `" + item["path"] + "`" for item in manifest["changes"])
    body = (f"Refs #{args.issue}\n\nImplements the approved Issue within the agent change allowlist.\n\n"
            f"Changed files:\n{changed}\n\nValidation: trusted patch policy passed; independent isolated "
            f"Maven verify passed before publication. [Run evidence]({run_url}).\n\n"
            f"Tested patch SHA-256: `{manifest['digest']}`\n\n"
            "Limitations: automated checks do not establish semantic correctness or guarantee secret detection. "
            "Independent PR CI and human review are required. No merge or deployment is authorized.")
    pr = write.call("POST", "/pulls", {"title": f"Agent implementation for Issue #{args.issue}",
        "head": branch, "base": manifest["default"], "body": body, "draft": False})
    # GITHUB_TOKEN has issues:write only in this trusted job; the App does not need it.
    read.call("POST", f"/issues/{args.issue}/comments", {"body": "Reviewable agent PR: " + pr["html_url"]})
    print("Published PR " + str(pr["number"]) + "; independent CI and human review required")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("gate", "collect", "verify", "fetch-artifact", "publish-check", "publish"))
    parser.add_argument("--repo", default=".")
    parser.add_argument("--base")
    parser.add_argument("--patch")
    parser.add_argument("--destination")
    parser.add_argument("--manifest")
    parser.add_argument("--issue", type=int)
    parser.add_argument("--actor")
    parser.add_argument("--issue-digest")
    parser.add_argument("--patch-digest")
    parser.add_argument("--label-id")
    parser.add_argument("--artifact-id")
    parser.add_argument("--artifact-name")
    parser.add_argument("--entry")
    args = parser.parse_args()
    try:
        if args.command == "gate":
            gate()
        elif args.command == "collect":
            collect(args.repo, args.base, args.destination)
        elif args.command == "verify":
            verify(args.repo, args.patch, args.base, args.destination)
        elif args.command == "fetch-artifact":
            fetch_artifact(args)
        elif args.command == "publish-check":
            publish(args)
        else:
            publish_api(args)
    except (Rejected, OSError, ValueError, KeyError, TypeError) as ex:
        print("Automation stopped safely: " + str(ex), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
