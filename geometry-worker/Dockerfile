FROM ghcr.io/astral-sh/uv:0.12.3 AS uv

FROM python:3.14.7-slim
COPY --from=uv /uv /uvx /bin/
RUN useradd --system --uid 10001 worker \
    && mkdir /data \
    && chown worker /data
WORKDIR /workspace
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project
COPY src/ src/
RUN uv sync --locked --no-dev
USER worker
ENV PATH="/workspace/.venv/bin:$PATH"
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD python -c "from scenery_foundry_worker.main import worker_identity; assert worker_identity()"
ENTRYPOINT ["geometry-worker"]
