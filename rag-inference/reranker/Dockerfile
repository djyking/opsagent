FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    MODEL_NAME=BAAI/bge-reranker-v2-m3

WORKDIR /app
COPY requirements.txt /app/requirements.txt
# 显式安装 CPU 版 PyTorch，避免无 GPU 的本地环境误下载数 GB 的 CUDA 运行库。
RUN pip install --no-cache-dir torch==2.8.0+cpu \
        --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir -r /app/requirements.txt
ENV MALLOC_ARENA_MAX=2
COPY app.py inference_pool.py /app/

EXPOSE 8010
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8010", "--workers", "1"]
