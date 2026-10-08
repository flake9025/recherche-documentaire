"""Controles locaux du benchmark, sans serveur Qdrant."""
import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("benchmark_qdrant", Path(__file__).with_name("benchmark-qdrant.py"))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class FakePeer:
    def __init__(self, number: int, replicas: int):
        self.url = f"http://node-{number}"
        self.number = number
        self.replicas = replicas

    def result(self, method: str, path: str):
        if path == "/cluster":
            return {"status": "enabled", "peers": {"1": {}, "2": {}, "3": {}}}
        return {
            "peer_id": self.number, "shard_transfers": [],
            "local_shards": [
                {"shard_id": shard, "state": "Active", "points_count": 100}
                for shard in range(6)
                if any((shard + copy) % 3 + 1 == self.number for copy in range(self.replicas))
            ],
        }


class BenchmarkTests(unittest.TestCase):
    def test_topology_distinguishes_logical_shards_and_replicas(self):
        peers = [FakePeer(number, 2) for number in range(1, 4)]
        layout = benchmark.cluster_layout(peers, "demo-scaling-test", 6, 2)
        self.assertEqual(layout["logicalShards"], 6)
        self.assertEqual(sum(len(node["localShards"]) for node in layout["nodes"]), 12)
        self.assertTrue(all(len(copies) == 2 for copies in layout["placements"].values()))

    def test_topology_refuses_missing_replica(self):
        peers = [FakePeer(number, 1) for number in range(1, 4)]
        with self.assertRaisesRegex(RuntimeError, "Topologie incomplete"):
            benchmark.cluster_layout(peers, "demo-scaling-test", 6, 2)

    def test_scope_validation_refuses_another_tenant(self):
        with self.assertRaisesRegex(RuntimeError, "Fuite de tenant"):
            benchmark.validate_hits({"points": [{"id": 1, "payload": {"tenant_id": "tenant-2"}}]}, "tenant-1", 1)

    def test_scope_validation_refuses_fewer_results(self):
        with self.assertRaisesRegex(RuntimeError, "Nombre de resultats"):
            benchmark.validate_hits({"points": []}, "tenant-1", 1)

    def test_scope_validation_refuses_duplicate_points(self):
        point = {"id": 1, "payload": {"tenant_id": "tenant-1"}}
        with self.assertRaisesRegex(RuntimeError, "Points dupliques"):
            benchmark.validate_hits({"points": [point, point]}, "tenant-1", 2)

    def test_scope_validation_accepts_authorized_results(self):
        benchmark.validate_hits({"points": [{"id": 1, "payload": {"tenant_id": "tenant-1"}}]}, "tenant-1", 1)

    def test_percentile_uses_nearest_rank(self):
        self.assertEqual(benchmark.percentile([4, 1, 3, 2], .95), 4)
        self.assertIsNone(benchmark.percentile([], .95))


if __name__ == "__main__":
    unittest.main()
