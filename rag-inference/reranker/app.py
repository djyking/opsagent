"""Optional BGE cross-encoder inference sidecar for OpsAgent."""

import os
from typing import List

from fastapi import FastAPI
from pydantic import BaseModel, Field
from sentence_transformers import CrossEncoder


class RerankRequest(BaseModel):
    """Validated query and passage batch."""

    query: str = Field(min_length=1, max_length=2000)
    documents: List[str] = Field(min_length=1, max_length=30)
    top_n: int = Field(default=6, ge=1, le=30)


app = FastAPI(title="OpsAgent BGE Reranker", version="1.0.0")
model = CrossEncoder(os.getenv("MODEL_NAME", "BAAI/bge-reranker-v2-m3"))


@app.get("/health")
def health() -> dict:
    """Return readiness after model initialization succeeds."""

    return {"status": "UP", "model": os.getenv("MODEL_NAME", "BAAI/bge-reranker-v2-m3")}


@app.post("/rerank")
def rerank(request: RerankRequest) -> dict:
    """Score query-passage pairs and return stable candidate indexes."""

    pairs = [(request.query, document) for document in request.documents]
    scores = model.predict(pairs).tolist()
    ranked = sorted(enumerate(scores), key=lambda item: item[1], reverse=True)
    return {
        "results": [
            {"index": index, "score": float(score)}
            for index, score in ranked[: request.top_n]
        ]
    }
