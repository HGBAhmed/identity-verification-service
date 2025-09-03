#FROM ubuntu:latest
#LABEL authors="ahmed"
#
#ENTRYPOINT ["top", "-b"]

FROM openjdk:21-jdk-slim

# Installation de Python et des dépendances système
RUN apt-get update && apt-get install -y \
    python3 \
    python3-pip \
    gcc \
    libgl1-mesa-glx \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copie et installe les dépendances Python
COPY requirements.txt .
RUN pip3 install -r requirements.txt

# Copie les scripts Python
COPY python_scripts/face_comparison.py python_scripts/document_extractor.py python_scripts/ai_server.py ./

# Modifie shared-temp dans ai_server.py
RUN sed -i 's|SHARED_TEMP_DIR = r".*"|SHARED_TEMP_DIR = "/app/shared-temp"|' ai_server.py

# Copie de l'application Java compilée
COPY build/libs/*-all.jar app.jar

# Crée les dossiers nécessaires
RUN mkdir -p /app/shared-temp

# Variables d'environnement
ENV PYTHONUNBUFFERED=1
ENV MICRONAUT_ENVIRONMENTS=docker

# Script de démarrage des deux services
RUN echo '#!/bin/bash\n\
python3 ai_server.py --host 0.0.0.0 --port 5000 &\n\
sleep 10\n\
java -jar app.jar\n\
' > start.sh && chmod +x start.sh

# Pour exposer les deux ports
EXPOSE 8080 5000

CMD ["./start.sh"]
