# Build du jar Spring Boot dans une image Maven avec JDK 25.
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace

# On copie uniquement le strict necessaire au build.
COPY pom.xml .
COPY src ./src

# Le packaging du conteneur ne rejoue pas les tests, deja executes en CI.
RUN mvn -B -DskipTests package

# Image d'execution minimale avec un JRE 25.
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tesseract-ocr \
    && mkdir -p /usr/share/tessdata \
    && curl -fsSL https://github.com/tesseract-ocr/tessdata/raw/4.00/fra.traineddata -o /usr/share/tessdata/fra.traineddata \
    && rm -rf /var/lib/apt/lists/*

# Repertoires utilises par H2, le stockage documentaire et Lucene.
RUN mkdir -p /app/storage/documents /app/storage/database /app/lucene-suggest

# On embarque uniquement le jar final produit a l'etape precedente.
COPY --from=build /workspace/target/poc-recherche-documentaire-*.jar /app/app.jar

EXPOSE 8080
# Les donnees applicatives restent persistantes hors du conteneur.
VOLUME ["/app/storage", "/app/lucene-suggest"]

# Demarrage standard de l'application Spring Boot.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
