"""Compare un Qdrant seul au cluster sur un corpus synthetique identique, avec filtres tenant."""
from __future__ import annotations

import argparse
import concurrent.futures
import http.client
import json
import math
import os
from pathlib import Path
import random
import sys
import threading
import time
import urllib.parse
import uuid


class ApiError(RuntimeError):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


class Api:
    def __init__(self, url: str, timeout: int = 60, keep_alive: bool = False):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password \
                or parsed.query or parsed.fragment:
            raise ValueError("URL Qdrant HTTP(S) sans credentials, query ou fragment attendue")
        self.url = url.rstrip("/")
        self.prefix = parsed.path.rstrip("/")
        connection = http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
        self.connection = connection(parsed.hostname, parsed.port, timeout=timeout)
        self.keep_alive = keep_alive
        self.headers = {"Content-Type": "application/json", "Connection": "keep-alive" if keep_alive else "close"}
        if os.environ.get("QDRANT_API_KEY"):
            self.headers["api-key"] = os.environ["QDRANT_API_KEY"]

    def request(self, method: str, path: str, payload: dict | bytes | None = None):
        data = payload if isinstance(payload, bytes) else encode(payload) if payload is not None else None
        self.connection.request(method, self.prefix + path, body=data, headers=self.headers)
        response = self.connection.getresponse()
        raw = response.read()
        if not self.keep_alive:
            self.connection.close()
        if not 200 <= response.status < 300:
            raise ApiError(response.status, f"{method} {self.url}{path}: HTTP {response.status}: {raw[:300]!r}")
        result = json.loads(raw)
        if not isinstance(result, dict):
            raise RuntimeError("Reponse Qdrant non objet")
        return result

    def result(self, method: str, path: str, payload: dict | bytes | None = None):
        response = self.request(method, path, payload)
        if response.get("status") != "ok" or "result" not in response:
            raise RuntimeError(f"Reponse Qdrant invalide: {response}")
        return response["result"]

    def close(self):
        self.connection.close()


def encode(payload: dict) -> bytes:
    return json.dumps(payload, separators=(",", ":"), allow_nan=False).encode()


def percentile(values: list[float], fraction: float) -> float | None:
    return round(sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)], 2) if values else None


def validate_hits(result: dict, tenant: str, limit: int):
    points = result.get("points")
    if not isinstance(points, list) or len(points) != limit:
        raise RuntimeError(f"Nombre de resultats incorrect pour {tenant}")
    if len({point["id"] for point in points}) != limit:
        raise RuntimeError("Points dupliques dans une recherche")
    if any(point.get("payload", {}).get("tenant_id") != tenant for point in points):
        raise RuntimeError(f"Fuite de tenant detectee pour {tenant}")


def cluster_layout(peers: list[Api], collection: str, shards: int, replicas: int):
    placements: dict[int, list[str]] = {}
    nodes = []
    expected_peers = None
    for peer in peers:
        membership = peer.result("GET", "/cluster")
        members = set(membership.get("peers", {}))
        if membership.get("status") != "enabled" or len(members) != len(peers):
            raise RuntimeError(f"{peer.url}: les {len(peers)} peers attendus ne sont pas presents")
        if expected_peers is not None and members != expected_peers:
            raise RuntimeError("Les noeuds ne voient pas le meme cluster")
        expected_peers = members
        layout = peer.result("GET", f"/collections/{collection}/cluster")
        if layout.get("shard_transfers"):
            raise RuntimeError("Transferts de shards encore en cours")
        local = layout.get("local_shards", [])
        for shard in local:
            if shard["state"] != "Active":
                raise RuntimeError(f"Shard {shard['shard_id']} non actif sur {peer.url}")
            placements.setdefault(shard["shard_id"], []).append(str(layout["peer_id"]))
        nodes.append({
            "url": peer.url, "peerId": str(layout["peer_id"]),
            "localShards": local,
        })
    if len({node["peerId"] for node in nodes}) != len(peers):
        raise RuntimeError("Les URLs de peers ne correspondent pas a des noeuds distincts")
    if set(placements) != set(range(shards)) or any(len(copies) != replicas for copies in placements.values()):
        raise RuntimeError(f"Topologie incomplete: attendu {shards} shards x {replicas} replicas, obtenu {placements}")
    if any(not node["localShards"] for node in nodes):
        raise RuntimeError("Un noeud ne contient aucun shard : demonstration du sharding incomplete")
    return {"logicalShards": shards, "replicas": replicas, "placements": placements, "nodes": nodes}


def measure(url: str, collection: str, queries: list[tuple[bytes, str]], workers: int, requests: int, limit: int):
    barrier = threading.Barrier(workers + 1)

    def run(worker: int):
        api = Api(url, keep_alive=True)
        timings, errors = [], []
        try:
            barrier.wait()
            for index in range(requests):
                query, tenant = queries[(worker * requests + index) % len(queries)]
                start = time.perf_counter()
                try:
                    result = api.result("POST", f"/collections/{collection}/points/query", query)
                    validate_hits(result, tenant, limit)
                    timings.append((time.perf_counter() - start) * 1000)
                except (ApiError, OSError, http.client.HTTPException) as error:
                    api.close()
                    errors.append(str(error))
        finally:
            api.close()
        return timings, errors

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [executor.submit(run, worker) for worker in range(workers)]
        start = time.perf_counter()
        barrier.wait()
        results = [future.result() for future in futures]
        elapsed = time.perf_counter() - start
    timings = [timing for result in results for timing in result[0]]
    errors = [error for result in results for error in result[1]]
    return {
        "workers": workers, "requests": workers * requests, "successfulRequests": len(timings),
        "successfulRps": round(len(timings) / elapsed, 2),
        "p50Ms": percentile(timings, .50), "p95Ms": percentile(timings, .95),
        "p99Ms": percentile(timings, .99), "httpFailures": len(errors), "errorSamples": errors[:3],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cluster-url", default="http://localhost:6342")
    parser.add_argument("--peer-urls", default="http://localhost:6343,http://localhost:6344,http://localhost:6345")
    parser.add_argument("--baseline-url", default="http://localhost:6346")
    parser.add_argument("--collection", default="demo-scaling-" + uuid.uuid4().hex[:12])
    parser.add_argument("--points", type=int, default=20000)
    parser.add_argument("--dimensions", type=int, default=384)
    parser.add_argument("--shards", type=int, default=6)
    parser.add_argument("--replicas", type=int, default=1,
                        help="1 pour comparer le meme cout de replication ; 2 pour la disponibilite")
    parser.add_argument("--tenants", type=int, default=6)
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--workers", default="1,8,24")
    parser.add_argument("--requests", type=int, default=100, help="Requetes par worker et par mesure")
    parser.add_argument("--seed", type=int, default=9025)
    parser.add_argument("--exact", action=argparse.BooleanOptionalAction, default=True,
                        help="Calcul exact identique par defaut ; --no-exact pour mesurer HNSW")
    parser.add_argument("--keep-collection", action="store_true")
    parser.add_argument("--output", type=Path, help="Rapport JSON, hors du depot de preference")
    args = parser.parse_args()
    try:
        workers = [int(value) for value in args.workers.split(",")]
    except ValueError:
        parser.error("--workers attend des nombres separes par des virgules")
    peer_urls = [url.strip().rstrip("/") for url in args.peer_urls.split(",")]
    if not args.collection.startswith("demo-scaling-") or not all(
            character.isascii() and (character.isalnum() or character in "-_") for character in args.collection):
        parser.error("Nom de collection synthetique requis: demo-scaling-<identifiant>")
    if not 1 <= args.shards <= 256 or not 1 <= args.replicas <= len(peer_urls) \
            or not 4 <= args.dimensions <= 4096 or not 1 <= args.tenants <= 256 \
            or not 1 <= args.limit <= 100 or not args.tenants * args.limit <= args.points <= 250000 \
            or not 1 <= args.requests <= 10000 or not workers or any(not 1 <= value <= 64 for value in workers) \
            or len(set(peer_urls)) != len(peer_urls):
        parser.error("Parametres hors limites ; chaque tenant doit avoir au moins --limit points")

    targets = {"baseline": Api(args.baseline_url), "cluster": Api(args.cluster_url)}
    peers = [Api(url) for url in peer_urls]
    created: list[Api] = []
    report = {
        "collection": args.collection, "seed": args.seed, "points": args.points, "dimensions": args.dimensions,
        "shards": args.shards, "tenants": args.tenants, "exact": args.exact, "limit": args.limit,
        "baselineReplicas": 1, "clusterReplicas": args.replicas,
        "scopeChecks": "passed", "measurements": {},
        "notes": [
            "Corpus, shards, requetes et limites identiques ; un CPU par noeud avec le Compose par defaut.",
            "Le cluster dispose donc de trois CPU, pas d'un budget total identique au mono-noeud.",
            "Mesure HTTP, gateway incluse pour le cluster ; ni DJL, ni PostgreSQL, ni sessions applicatives.",
            "Trois conteneurs sur le meme hote ne prouvent pas un scaling de production entre machines.",
        ],
    }
    if args.replicas != 1:
        report["notes"].append("Replication differente : cette execution n'est pas une comparaison pure de capacite.")
    try:
        versions = {name: api.request("GET", "/")["version"] for name, api in targets.items()}
        if len(set(versions.values())) != 1:
            raise RuntimeError(f"Versions Qdrant differentes: {versions}")
        report["versions"] = versions
        for api in [*targets.values(), *peers]:
            try:
                api.result("GET", f"/collections/{args.collection}")
            except ApiError as error:
                if error.status != 404:
                    raise
            else:
                raise RuntimeError(f"Collection existante sur {api.url}; aucun remplacement ni suppression autorise")
        for name, api in targets.items():
            api.result("PUT", f"/collections/{args.collection}", {
                "vectors": {"size": args.dimensions, "distance": "Cosine"},
                "shard_number": args.shards, "replication_factor": 1 if name == "baseline" else args.replicas,
                "write_consistency_factor": 1, "optimizers_config": {"indexing_threshold": 1024},
            })
            created.append(api)
            api.result("PUT", f"/collections/{args.collection}/index?wait=true", {
                "field_name": "tenant_id", "field_schema": {"type": "keyword", "is_tenant": True},
            })

        rng = random.Random(args.seed)
        queries: list[tuple[bytes, str]] = []
        write_times = {name: 0.0 for name in targets}
        for offset in range(0, args.points, 128):
            points = []
            for point_id in range(offset, min(offset + 128, args.points)):
                vector = [round(rng.uniform(-1, 1), 7) for _ in range(args.dimensions)]
                tenant = f"tenant-{point_id % args.tenants}"
                points.append({"id": point_id, "vector": vector, "payload": {"tenant_id": tenant}})
                if len(queries) < max(32, args.tenants):
                    queries.append((encode({
                        "query": vector, "filter": {"must": [{"key": "tenant_id", "match": {"value": tenant}}]},
                        "limit": args.limit, "with_payload": ["tenant_id"], "with_vector": False,
                        "params": {"exact": args.exact},
                    }), tenant))
            data = encode({"points": points})
            for name, api in targets.items():
                start = time.perf_counter()
                api.result("PUT", f"/collections/{args.collection}/points?wait=true", data)
                write_times[name] += time.perf_counter() - start
        report["writeSeconds"] = {name: round(seconds, 2) for name, seconds in write_times.items()}

        deadline = time.monotonic() + 180
        while True:
            try:
                report["topology"] = cluster_layout(peers, args.collection, args.shards, args.replicas)
                break
            except RuntimeError as error:
                if time.monotonic() >= deadline:
                    raise RuntimeError(f"Cluster non pret apres 180 secondes: {error}") from error
                time.sleep(2)
        for api in [*targets.values(), *peers]:
            count = api.result("POST", f"/collections/{args.collection}/points/count", {"exact": True})["count"]
            if count != args.points:
                raise RuntimeError(f"{api.url}: attendu {args.points} points logiques, obtenu {count}")
        deadline = time.monotonic() + 180
        while True:
            info = {name: api.result("GET", f"/collections/{args.collection}") for name, api in targets.items()}
            if all(value["status"] == "green" and value["optimizer_status"] == "ok" for value in info.values()):
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("Optimisation des collections non terminee apres 180 secondes")
            time.sleep(2)
        report["indexedVectors"] = {name: value["indexed_vectors_count"] for name, value in info.items()}
        for api in targets.values():
            for query, tenant in queries:
                validate_hits(api.result("POST", f"/collections/{args.collection}/points/query", query), tenant, args.limit)
        for index, concurrency in enumerate(workers):
            # Alterner l'ordre pour ne pas toujours favoriser le meme moteur apres l'echauffement.
            names = list(targets) if index % 2 == 0 else list(reversed(targets))
            for name in names:
                result = measure(targets[name].url, args.collection, queries, concurrency, args.requests, args.limit)
                report["measurements"].setdefault(name, []).append(result)
        print(json.dumps(report, indent=2))
        if args.output:
            args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        return int(any(measurement["httpFailures"] for measurements in report["measurements"].values()
                       for measurement in measurements))
    finally:
        cleanup_errors = []
        if not args.keep_collection:
            for api in created:
                try:
                    api.result("DELETE", f"/collections/{args.collection}")
                except (RuntimeError, OSError, http.client.HTTPException) as error:
                    cleanup_errors.append(str(error))
        for api in [*targets.values(), *peers]:
            api.close()
        if cleanup_errors:
            raise RuntimeError("Nettoyage des collections creees incomplet: " + "; ".join(cleanup_errors))


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, ValueError, OSError, http.client.HTTPException) as error:
        print(f"Benchmark interrompu : {error}", file=sys.stderr)
        sys.exit(1)
