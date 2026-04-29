FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=5s --retries=5 --start-period=20s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8090/api/faiss/stats')"
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8090"]
