"""Charge multi-utilisateur avec sessions/CSRF et verification des perimetres."""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import os
import sys
import time
import urllib.error
from poc_client import Session


def percentile(values: list[float], fraction: float) -> float:
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--requests", type=int, default=100, help="Requetes par worker")
    parser.add_argument("--query", default="rapport")
    parser.add_argument("--ai-model", help="Active la synthese pour mesurer aussi la gateway")
    parser.add_argument("--timeout", type=int, default=90)
    args = parser.parse_args()
    password = os.environ.get("LOAD_PASSWORD", "")
    names = [name.strip() for name in os.environ.get("LOAD_USERS", "alice,bob,carol,david,eve,admin").split(",")]
    if not password or not all(names) or not 1 <= args.workers <= 64 or not 1 <= args.requests <= 10000 or args.timeout < 1:
        parser.error("Definir LOAD_PASSWORD, LOAD_USERS ; workers=1..64, requests=1..10000, timeout>=1")

    baseline = [Session(args.url, name, password, args.timeout) for name in names]
    admin = next((session for session in baseline if session.user["role"] == "ADMIN"), None)
    if admin is None:
        parser.error("Inclure un administrateur dans LOAD_USERS pour verifier la hierarchie")
    all_documents = admin.json("/api/documents")
    accounts = admin.json("/api/admin/users")
    owners = {document["ownerId"] for document in all_documents if document.get("ownerId") is not None}
    if len(owners) < 2:
        parser.error("Importer des documents sous au moins deux comptes distincts avant le benchmark")

    scopes = {}
    denied_checks = 0
    for session in baseline:
        user = session.user
        visible_owners = {user["id"]}
        if user["role"] == "MANAGER":
            visible_owners.update(account["id"] for account in accounts
                                  if account["role"] == "USER" and account["managerId"] == user["id"])
        expected = {document["id"] for document in all_documents
                    if user["role"] == "ADMIN" or document.get("ownerId") in visible_owners}
        actual = {document["id"] for document in session.json("/api/documents")}
        if actual != expected:
            raise RuntimeError(f"Perimetre documentaire incorrect pour {user['username']}")
        scopes[user["username"]] = expected
        forbidden = next((document for document in all_documents if document["id"] not in expected), None)
        if forbidden:
            try:
                with session.request(f"/api/documents/{forbidden['id']}/file"):
                    raise RuntimeError(f"Telechargement hors perimetre autorise pour {user['username']}")
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
                denied_checks += 1

    def run(worker: int):
        username = names[worker % len(names)]
        session = Session(args.url, username, password, args.timeout)
        timings, failures, ai_failures = [], 0, 0
        for _ in range(args.requests):
            start = time.perf_counter()
            try:
                result = session.json("/api/search/", {
                    "query": args.query,
                    "summarize": bool(args.ai_model),
                    "aiModel": args.ai_model,
                })
                ids = {int(fragment["id"]) for fragment in result["fragments"]}
                source_ids = {int(source["documentId"]) for source in (result.get("summary") or {}).get("sources", [])}
                if not ids <= scopes[username] or not source_ids <= ids:
                    raise RuntimeError(f"Fuite documentaire ou IA pour {username}")
                if result.get("summaryError"):
                    ai_failures += 1
            except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError):
                failures += 1
            timings.append((time.perf_counter() - start) * 1000)
        return timings, failures, ai_failures

    start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        results = list(executor.map(run, range(args.workers)))
    elapsed = time.perf_counter() - start
    timings = [value for result in results for value in result[0]]
    failures = sum(result[1] for result in results)
    ai_failures = sum(result[2] for result in results)
    print(json.dumps({
        "requests": len(timings), "workers": args.workers,
        "requestsPerSecond": round(len(timings) / elapsed, 2),
        "p50Ms": round(percentile(timings, .50), 2),
        "p95Ms": round(percentile(timings, .95), 2),
        "p99Ms": round(percentile(timings, .99), 2),
        "httpFailures": failures, "aiFailures": ai_failures,
        "forbiddenDownloadsChecked": denied_checks,
        "scopeChecks": "passed",
    }, indent=2))
    return 1 if failures or ai_failures else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, ValueError, urllib.error.URLError, TimeoutError) as error:
        print(f"Benchmark interrompu : {error}", file=sys.stderr)
        sys.exit(1)
