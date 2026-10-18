FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --create-home --uid 10001 reader
COPY server server
COPY web web
RUN mkdir /app/data && chown reader:reader /app/data
USER reader
ENV SAMACHA_DATA=/app/data
EXPOSE 8787
CMD ["python", "-m", "server.app", "--host", "0.0.0.0"]
