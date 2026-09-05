"""Optional BGE cross-encoder inference sidecar for OpsAgent."""

import os
from time import perf_counter
from typing import List

import torch
from fastapi import FastAPI
from pydantic import BaseModel, Field
from sentence_transformers import CrossEncoder


class RerankRequest(BaseModel):
    """Validated query and passage batch."""

    query: str = Field(min_length=1, max_length=2000)
    documents: List[str] = Field(min_length=1, max_length=30)
    top_n: int = Field(default=6, ge=1, le=30)


app = FastAPI(title="OpsAgent BGE Reranker", version="1.0.0")
max_length = int(os.getenv("RERANK_MAX_LENGTH", "512"))
batch_size = int(os.getenv("RERANK_BATCH_SIZE", "8"))
cpu_threads = int(os.getenv("RERANK_CPU_THREADS", "4"))
torch.set_num_threads(cpu_threads)
model = CrossEncoder(
    os.getenv("MODEL_NAME", "BAAI/bge-reranker-v2-m3"), max_length=max_length
)


@app.get("/health")
def health() -> dict:
    """Return readiness after model initialization succeeds."""

    return {
        "status": "UP",
        "model": os.getenv("MODEL_NAME", "BAAI/bge-reranker-v2-m3"),
        "max_length": max_length,
        "batch_size": batch_size,
        "cpu_threads": cpu_threads,
    }


@app.post("/rerank")
def rerank(request: RerankRequest) -> dict:
    """Score query-passage pairs and return stable candidate indexes."""

    started = perf_counter()
    pairs = [(request.query, document) for document in request.documents]
    scores = model.predict(pairs, batch_size=batch_size, show_progress_bar=False).tolist()
    ranked = sorted(enumerate(scores), key=lambda item: item[1], reverse=True)
    return {
        "duration_ms": round((perf_counter() - started) * 1000),
        "results": [
            {"index": index, "score": float(score)}
            for index, score in ranked[: request.top_n]
        ]
    }
