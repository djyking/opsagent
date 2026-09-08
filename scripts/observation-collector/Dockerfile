FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && useradd --uid 10001 --create-home collector
COPY collector.py windows_host.py linux_host.py ./
USER collector
ENTRYPOINT ["python", "/app/collector.py", "--config", "/run/opsagent/collector.json"]
