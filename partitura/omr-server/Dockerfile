FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends poppler-utils libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir homr pillow
# baixa os modelos agora, para a primeira leitura não demorar
RUN homr --init
WORKDIR /app
COPY server.py /app/server.py
ENV PORT=8080 DATA_DIR=/app/data
VOLUME /app/data
EXPOSE 8080
# usuário comum: se alguém invadir o servidor, não é root
RUN useradd -m app && mkdir -p /app/data && chown -R app /app
USER app
CMD ["python", "/app/server.py"]
