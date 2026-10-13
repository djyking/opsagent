"""Optional BGE cross-encoder inference sidecar for OpsAgent."""

import os
import asyncio
from contextlib import asynccontextmanager
from time import perf_counter
from typing import List

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from inference_pool import InferenceBusyError, InferencePool


class RerankRequest(BaseModel):
    """Validated query and passage batch."""

    query: str = Field(min_length=1, max_length=2000)
    documents: List[str] = Field(min_length=1, max_length=30)
    top_n: int = Field(default=6, ge=1, le=30)


def positive_env(name: str, default: int) -> int:
    value = int(os.getenv(name, str(default)))
    if value < 1:
        raise ValueError(f"{name} must be positive")
    return value


model_name = os.getenv("MODEL_NAME", "BAAI/bge-reranker-v2-m3")
max_length = positive_env("RERANK_MAX_LENGTH", 512)
batch_size = positive_env("RERANK_BATCH_SIZE", 8)
cpu_threads = positive_env("RERANK_CPU_THREADS", 4)
interop_threads = positive_env("RERANK_INTEROP_THREADS", 1)
max_concurrency = positive_env("RERANK_MAX_CONCURRENCY", 1)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Set native-library defaults before importing/loading the model. Explicit
    # operator settings still take precedence. The demo profile uses 2 threads.
    os.environ.setdefault("OMP_NUM_THREADS", str(cpu_threads))
    os.environ.setdefault("MKL_NUM_THREADS", str(cpu_threads))
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    import torch
    torch.set_num_threads(cpu_threads)
    torch.set_num_interop_threads(interop_threads)
    from sentence_transformers import CrossEncoder

    app.state.model = CrossEncoder(model_name, max_length=max_length, device="cpu")
    app.state.pool = InferencePool(max_concurrency)
    try:
        yield
    finally:
        # Wait for admitted work before releasing the model during shutdown.
        app.state.pool.shutdown()
        del app.state.model


app = FastAPI(title="OpsAgent BGE Reranker", version="1.1.0", lifespan=lifespan)


@app.get("/health")
async def health() -> dict:
    """Return readiness after model initialization succeeds."""

    return {
        "status": "UP",
        "model": model_name,
        "model_loaded": True,
        "max_length": max_length,
        "batch_size": batch_size,
        "cpu_threads": cpu_threads,
        "interop_threads": interop_threads,
        **app.state.pool.snapshot(),
    }


@app.post("/rerank")
async def rerank(request: RerankRequest) -> dict:
    """Score query-passage pairs and return stable candidate indexes."""

    try:
        future = app.state.pool.submit(lambda: score(request))
    except InferenceBusyError as exception:
        # The Java caller already falls back to its RRF ranking on remote errors.
        raise HTTPException(
            status_code=503,
            detail="Reranker is busy; use retrieval ranking or retry later",
            headers={"Retry-After": "1"},
        ) from exception
    return await asyncio.wrap_future(future)


def score(request: RerankRequest) -> dict:
    started = perf_counter()
    pairs = [(request.query, document) for document in request.documents]
    scores = app.state.model.predict(
        pairs, batch_size=batch_size, show_progress_bar=False
    ).tolist()
    ranked = sorted(enumerate(scores), key=lambda item: item[1], reverse=True)
    return {
        "duration_ms": round((perf_counter() - started) * 1000),
        "results": [
            {"index": index, "score": float(score)}
            for index, score in ranked[: request.top_n]
        ]
    }
