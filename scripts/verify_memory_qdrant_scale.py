#!/usr/bin/env python3
"""Exercise Qdrant HNSW at 32k synthetic 1024-dimensional points.

The script starts a disposable, unmounted Qdrant container and uses a unique
collection. It never reads or writes the application's MySQL/Qdrant data.
Requires Docker, Python 3, and the project's `rtk` command.
"""

from __future__ import annotations

import json
import math
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid


IMAGE = "qdrant/qdrant:v1.19.1"
DIMENSIONS = 1024
POINT_COUNT = 32_000
BATCH_SIZE = 256
QUERY_COUNT = 40
USER_A = 9_100_001
USER_B = 9_100_002
MODEL = "memory-scale-test"
USER_A_COUNT = POINT_COUNT // 2
RELEVANT_COUNT = 200


def docker(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(["rtk", "docker", *args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace")
    if check and result.returncode != 0:
        raise RuntimeError((result.stderr or result.stdout).strip())
    return result


def free_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def request(base_url: str, method: str, path: str, body: object | None = None,
            timeout: float = 30.0) -> tuple[dict, float]:
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    req = urllib.request.Request(
        base_url + path,
        data=data,
        headers={"Content-Type": "application/json"},
        method=method,
    )
    started = time.perf_counter()
    with urllib.request.urlopen(req, timeout=timeout) as response:
        payload = json.loads(response.read())
    return payload, (time.perf_counter() - started) * 1000


def wait_ready(base_url: str) -> None:
    deadline = time.monotonic() + 45
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            request(base_url, "GET", "/", timeout=2)
            return
        except (OSError, urllib.error.URLError) as error:
            last_error = error
            time.sleep(1)
    raise RuntimeError(f"Qdrant did not become ready: {last_error}")


def point_vector(point_id: int) -> list[float]:
    vector = [0.0] * DIMENSIONS
    if point_id <= RELEVANT_COUNT or point_id > USER_A_COUNT:
        vector[0] = 1.0
    else:
        vector[1 + (point_id % (DIMENSIONS - 1))] = 1.0
    return vector


def create_points(start: int, end: int) -> list[dict]:
    points = []
    for point_id in range(start, end):
        user_id = USER_A if point_id <= USER_A_COUNT else USER_B
        points.append({
            "id": point_id,
            "vector": point_vector(point_id),
            "payload": {
                "user_id": user_id,
                "source_kind": "MEMORY_ITEM",
                "source_id": point_id,
                "embedding_model": MODEL,
            },
        })
    return points


def percentile(values: list[float], percentile_value: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(percentile_value * len(ordered)) - 1)]


def main() -> None:
    suffix = uuid.uuid4().hex[:10]
    container = f"xiaochuling-qdrant-scale-{suffix}"
    collection = f"xiaochuling_memory_scale_{suffix}"
    port = 0
    base_url = ""
    started = False
    collection_created = False

    for _ in range(5):
        port = free_port()
        result = docker(
            "run", "--detach", "--rm", "--name", container,
            "--publish", f"127.0.0.1:{port}:6333", IMAGE, check=False,
        )
        if result.returncode == 0:
            started = True
            base_url = f"http://127.0.0.1:{port}"
            break
        details = (result.stderr or result.stdout).strip()
        if "ports are not available" not in details and "permission" not in details.lower():
            raise RuntimeError(details)
    if not started:
        raise RuntimeError("Could not bind a disposable Qdrant container to an available local port")

    try:
        wait_ready(base_url)
        request(base_url, "PUT", f"/collections/{collection}", {
            "vectors": {"size": DIMENSIONS, "distance": "Cosine"},
            "hnsw_config": {"m": 16, "ef_construct": 100, "full_scan_threshold": 10_000},
        })
        collection_created = True
        for field_name, field_schema in (("user_id", "integer"), ("embedding_model", "keyword")):
            request(base_url, "PUT", f"/collections/{collection}/index", {
                "field_name": field_name,
                "field_schema": field_schema,
            })

        for start in range(1, POINT_COUNT + 1, BATCH_SIZE):
            end = min(start + BATCH_SIZE, POINT_COUNT + 1)
            request(base_url, "PUT", f"/collections/{collection}/points?wait=true", {
                "points": create_points(start, end),
            }, timeout=90)
            print(f"upserted {end - 1}/{POINT_COUNT} synthetic vectors", flush=True)

        index_deadline = time.monotonic() + 120
        collection_status = {}
        while time.monotonic() < index_deadline:
            response, _ = request(base_url, "GET", f"/collections/{collection}")
            collection_status = response.get("result", {})
            if collection_status.get("indexed_vectors_count", 0) >= POINT_COUNT:
                break
            time.sleep(2)

        point_count = collection_status.get("points_count", 0)
        indexed_count = collection_status.get("indexed_vectors_count", 0)
        if point_count != POINT_COUNT:
            raise AssertionError(f"Expected {POINT_COUNT} points; got {point_count}")
        if indexed_count < POINT_COUNT:
            raise AssertionError(f"HNSW indexing did not reach {POINT_COUNT}; got {indexed_count}")

        query_vector = [1.0] + [0.0] * (DIMENSIONS - 1)
        query = {
            "query": query_vector,
            "filter": {"must": [
                {"key": "user_id", "match": {"value": USER_A}},
                {"key": "embedding_model", "match": {"value": MODEL}},
            ]},
            "limit": 20,
            "with_payload": True,
            "with_vector": False,
        }

        latencies = []
        last_points = []
        for _ in range(QUERY_COUNT):
            response, latency = request(base_url, "POST", f"/collections/{collection}/points/query", query)
            latencies.append(latency)
            last_points = response.get("result", {}).get("points", [])

        if len(last_points) != 20:
            raise AssertionError(f"Expected 20 search hits; got {len(last_points)}")
        leaked_users = sorted({point.get("payload", {}).get("user_id") for point in last_points
                               if point.get("payload", {}).get("user_id") != USER_A})
        if leaked_users:
            raise AssertionError(f"User filter leaked payloads: {leaked_users}")
        relevant_hits = sum(1 for point in last_points
                            if 1 <= point.get("payload", {}).get("source_id", 0) <= RELEVANT_COUNT)
        if relevant_hits < 18:
            raise AssertionError(f"Expected at least 18/20 relevant top hits; got {relevant_hits}/20")

        print("\nQdrant synthetic scale verification passed")
        print(f"points={point_count}; hnsw_indexed={indexed_count}; dimensions={DIMENSIONS}")
        print(f"user_isolation={len(last_points)}/{len(last_points)} hits belong to the requested user")
        print(f"top20_relevant_hits={relevant_hits}/20")
        print(f"local_http_latency p50={percentile(latencies, 0.50):.2f} ms, "
              f"p95={percentile(latencies, 0.95):.2f} ms ({QUERY_COUNT} queries)")
    finally:
        if collection_created:
            try:
                request(base_url, "DELETE", f"/collections/{collection}", timeout=10)
            except Exception as error:  # the ephemeral container is also removed below
                print(f"temporary collection cleanup failed; ephemeral container removal will discard it: {error}",
                      flush=True)
        if started:
            stopped = docker("stop", container, check=False)
            if stopped.returncode != 0:
                raise RuntimeError(f"Could not stop temporary Qdrant container {container}")
            print("temporary Qdrant container stopped and auto-removed", flush=True)


if __name__ == "__main__":
    main()
