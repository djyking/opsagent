# Reranker resource budget

The sidecar keeps the configured CrossEncoder model and the existing scoring
format. The demo deployment should override the following environment variables:

| Variable | General default | Low-concurrency demo |
| --- | --- | --- |
| `RERANK_BATCH_SIZE` | `8` | `2` |
| `RERANK_CPU_THREADS` | `4` | `2` |
| `RERANK_INTEROP_THREADS` | `1` | `1` |
| `RERANK_MAX_CONCURRENCY` | `1` | `1` |
| `RERANK_MAX_LENGTH` | `512` | `512` (unchanged) |

Batch size changes inference working memory and speed, not the candidate count
or ranking algorithm. Keep the selected model unchanged. Smaller batches may
increase latency, so verify the Java caller's rerank timeout with real queries.

One Uvicorn process owns one model. The Docker command fixes `--workers 1` so a
worker-count environment variable cannot accidentally duplicate model memory.
`MALLOC_ARENA_MAX=2` limits glibc allocator arenas. Torch's intra-op and inter-op
thread counts are explicit; tokenizer parallelism defaults to disabled.

At most `RERANK_MAX_CONCURRENCY` inference calls are admitted. Extra calls get
HTTP 503 with `Retry-After: 1` immediately; they are not queued for the model.
OpsAgent's existing `RerankService` catches remote errors and retains RRF retrieval
order for that request. `/health` stays responsive and reports model readiness,
active calls, the configured concurrency limit, and the rejected-call count.
A disconnected or cancelled caller does not release its slot while PyTorch is
still running. The slot is released when its worker finishes, including failures.

This is a bound on inference concurrency, not an HTTP request-body size limit.
Public ingress should separately bound request size and access to this internal
sidecar. It does not unload the model between requests: repeated loading would
make the demo slower and would not reduce its peak memory requirement.

Run the focused tests inside an environment with the sidecar dependencies:

```sh
python -m unittest discover -s tests -v
```

The tests use a fake model and exercise overload, cancelled requests, failures,
shutdown, health responsiveness, and preservation of stable candidate indexes.
No model download or external AI request is needed for these tests. Actual memory
and latency must still be measured with the deployed model after applying the
demo resource profile.
