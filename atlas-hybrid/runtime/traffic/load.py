"""Continuous load through HAProxy for the traffic demo (runtime/traffic/traffic-demo.sh).

Usage: python3 -I load.py <host:port> <threads> <stop-file> <result-file> [post-ms]

Each thread loops over two kinds of request until <stop-file> exists:
  GET  /traffic-demo/api/slow?ms=300     HTTP work (replayable: HAProxy may retry it elsewhere)
  POST /traffic-demo/api/orders?ms=<post-ms, default 1500>  EJB + JTA transaction + JDBC (never replayed)
Every response is recorded as "<epoch ms> <status> <instance>"; connection errors are recorded as
status 0. A summary is printed at the end.
"""
import http.client
import json
import os
import sys
import threading
import time

host, port = sys.argv[1].split(":")
threads = int(sys.argv[2])
stop_file, result_file = sys.argv[3], sys.argv[4]
post_ms = int(sys.argv[5]) if len(sys.argv) > 5 else 1500
requests = [("GET", "/traffic-demo/api/slow?ms=300"), ("POST", "/traffic-demo/api/orders?ms=%d" % post_ms)]
results = []
lock = threading.Lock()


def worker(index):
    connection = None
    n = 0
    while not os.path.exists(stop_file):
        method, path = requests[(index + n) % len(requests)]
        n += 1
        status, instance = 0, "-"
        try:
            if connection is None:
                connection = http.client.HTTPConnection(host, int(port), timeout=30)
            connection.request(method, path, body=b"" if method == "POST" else None)
            response = connection.getresponse()
            body = response.read()
            status = response.status
            if status == 200:
                instance = json.loads(body).get("instance", "?")
            if response.getheader("Connection", "").lower() == "close":
                connection.close()
                connection = None
        except Exception as e:  # connection reset, refused, timeout...
            instance = type(e).__name__
            if connection is not None:
                connection.close()
            connection = None
        with lock:
            results.append((int(time.time() * 1000), status, instance + ("" if status == 200 else "@" + method)))


workers = [threading.Thread(target=worker, args=(i,)) for i in range(threads)]
for w in workers:
    w.start()
for w in workers:
    w.join()

with open(result_file, "w") as out:
    for line in sorted(results):
        out.write("%d %d %s\n" % line)
failed = [r for r in results if r[1] != 200]
by_instance = {}
for r in results:
    if r[1] == 200:
        by_instance[r[2]] = by_instance.get(r[2], 0) + 1
print(json.dumps({"requests": len(results), "failed": len(failed), "byInstance": by_instance,
                  "failures": sorted({"%s %s" % (r[1], r[2]) for r in failed})}))
