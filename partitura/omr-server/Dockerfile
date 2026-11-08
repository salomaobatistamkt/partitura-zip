FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends poppler-utils libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir homr
# baixa os modelos agora, para a primeira leitura não demorar
RUN homr --init
WORKDIR /app
COPY server.py /app/server.py
ENV PORT=8080
EXPOSE 8080
CMD ["python", "/app/server.py"]
