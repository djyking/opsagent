"""Bound inference work without queuing requests or abandoning running work."""

from concurrent.futures import Future, ThreadPoolExecutor
from threading import Lock
from typing import Callable


class InferenceBusyError(RuntimeError):
    """No inference slot is available; callers may use their retrieval fallback."""


class InferencePool:
    """Own slots until the worker finishes, even when its HTTP request is cancelled."""

    def __init__(self, max_concurrency: int = 1) -> None:
        if max_concurrency < 1:
            raise ValueError("max_concurrency must be positive")
        self._limit = max_concurrency
        self._active = 0
        self._rejected = 0
        self._closed = False
        self._lock = Lock()
        self._executor = ThreadPoolExecutor(
            max_workers=max_concurrency, thread_name_prefix="rerank"
        )

    def submit(self, work: Callable[[], dict]) -> Future:
        with self._lock:
            if self._closed or self._active >= self._limit:
                self._rejected += 1
                raise InferenceBusyError("Reranker is busy")
            self._active += 1
        try:
            future = self._executor.submit(work)
        except BaseException:
            self._release()
            raise
        # Attach to the worker's concurrent Future, not the cancellable asyncio
        # wrapper: a disconnected client cannot free a still-running model slot.
        future.add_done_callback(lambda _: self._release())
        return future

    def _release(self) -> None:
        with self._lock:
            self._active -= 1

    def snapshot(self) -> dict:
        with self._lock:
            return {
                "max_concurrency": self._limit,
                "active_requests": self._active,
                "busy": self._closed or self._active >= self._limit,
                "rejected_requests": self._rejected,
            }

    def shutdown(self) -> None:
        with self._lock:
            self._closed = True
        self._executor.shutdown(wait=True, cancel_futures=True)
