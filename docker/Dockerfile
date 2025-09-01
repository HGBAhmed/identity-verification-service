FROM python:3.10-slim

RUN apt-get update && apt-get install -y \
    gcc libgl1-mesa-glx libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY face_comparison.py document_extractor.py ai_server.py ./
RUN mkdir -p /app/shared-temp

ENV PYTHONUNBUFFERED=1
EXPOSE 5000

CMD ["python", "ai_server.py", "--host", "0.0.0.0", "--port", "5000"]