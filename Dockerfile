# Build du jar Spring Boot dans une image Maven avec JDK 25.
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace

# On copie uniquement le strict necessaire au build.
COPY pom.xml .
COPY src ./src

# Le packaging du conteneur ne rejoue pas les tests, deja executes en CI.
RUN mvn -B -DskipTests package

# Extraction des couches du jar en etape intermediaire.
# Les dependances (rarement modifiees) et le code applicatif
# occupent des couches Docker separees : seule la couche applicative
# est retransferee lors d'un rebuild apres une simple modification du code.
FROM eclipse-temurin:25-jre AS extract
WORKDIR /workspace
COPY --from=build /workspace/target/poc-recherche-documentaire-*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract --destination extracted

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

# Couche 1 : dependances externes (stables, rarement retransferees).
COPY --from=extract /workspace/extracted/dependencies/ ./
# Couche 2 : Spring Boot loader (stable).
COPY --from=extract /workspace/extracted/spring-boot-loader/ ./
# Couche 3 : dependances SNAPSHOT (semi-stables).
COPY --from=extract /workspace/extracted/snapshot-dependencies/ ./
# Couche 4 : code applicatif (change a chaque release).
COPY --from=extract /workspace/extracted/application/ ./

EXPOSE 8080
# Les donnees applicatives et le cache DJL restent persistants hors du conteneur.
# Monter /root/.djl.ai evite de retelecharger PyTorch (~600 MB) a chaque redemarrage.
VOLUME ["/app/storage", "/app/lucene-suggest", "/root/.djl.ai"]

# JAVA_OPTS permet de surcharger les options JVM au runtime :
#   docker run -e JAVA_OPTS="-Xmx512m" ...
# MaxRAMPercentage=50 laisse de la memoire pour Tesseract et les buffers natifs.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+UseContainerSupport"
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} org.springframework.boot.loader.launch.JarLauncher"]
