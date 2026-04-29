# Recherche Documentaire Augmentee par l'IA

POC Spring Boot de recherche documentaire combinant OCR, indexation plein texte, recherche semantique par embeddings et interface de demonstration locale.

![Screenshot](screenshot.png)

## Objectif

Le projet sert a demonstrer une architecture simple mais evolutive pour:

- ingerer des documents et extraire leur texte
- indexer ce contenu avec plusieurs strategies
- rechercher soit en plein texte, soit en semantique
- persister des snapshots d'index chiffres
- preparer une evolution vers des stores vectoriels plus scalables

Ce n'est pas un produit fini. C'est un POC structure pour permettre des discussions techniques sur l'indexation, la recherche, les feature flags, la securite et les choix d'architecture.

## Fonctionnalites

- upload et stockage local des documents
- metadonnees documentaires en base H2
- OCR via PDFBox, Tika et Tesseract selon la configuration
- mode d'indexation `lucene`
- mode d'indexation `bert`
- mode d'indexation `lucene-vector`
- mode de recherche `lucene`
- mode de recherche `bert`
- mode de recherche `lucene-vector`
- autocompletion auteur avec Lucene
- snapshots chiffres des index documentaires
- API REST Swagger + UI web locale

## Architecture

### Documents et OCR

Les documents sont stockes via `StorageService`.
Les metadonnees sont persistees via `DocumentService`.
Le texte est extrait via `OCRServiceFactory`.

Par defaut, `application.yml` cible un poste Windows local avec une installation
Tesseract classique. Les profils `lucene`, `lucene-vector` et `bert`
surchargent le `dataPath` Tesseract pour un environnement Linux/Docker.

### Trois pipelines de recherche

Le projet separe maintenant explicitement:

- `app.indexer.default`
- `app.search.default`

Cela permet de choisir distinctement le moteur utilise pour l'indexation et celui utilise pour la recherche, meme si dans la configuration courante les deux pointent vers `bert`.

Les moteurs a base d'embeddings (`bert` et `lucene-vector`) indexent desormais
le meme contenu textuel que Lucene:

- titre
- auteur
- categorie
- nom de fichier
- contenu OCR

#### Mode Lucene

- index documentaire en memoire via `LuceneConfig`
- persistance du snapshot Lucene dans la table `lucene_index`
- recherche plein texte avec filtres categorie/auteur/date

#### Mode BERT

- generation d'embeddings via DJL + Hugging Face
- store d'embeddings abstrait via `BertEmbeddingsStore`
- recherche semantique + reranking lexical
- persistance du snapshot BERT dans la table `bert_embeddings_index`

#### Mode Lucene Vector

- index documentaire Lucene avec champs vectoriels natifs
- generation des embeddings via `BertEmbeddingsService`
- recherche KNN Lucene avec filtres categorie/auteur/date
- persistance du snapshot Lucene vectoriel dans la table `lucene_vector_index`

## Stores d'embeddings BERT

Le projet supporte plusieurs implementations de store vectoriel:

- `hashmap`
- `faiss-remote`
- `qdrant`
- `milvus`

### Store par defaut

Le store par defaut est configure par:

```yaml
app:
  embeddings:
    store:
      default: hashmap
```

### `hashmap`

Mode local du POC:

- store en memoire via `ConcurrentHashMap`
- recherche par scan du store
- calcul du score semantique en RAM

### `faiss-remote`

Service Python FAISS livre dans `faiss-service/`:

- API REST FastAPI (port `8090`)
- index `IndexFlatIP` avec normalisation L2 (similarite cosinus)
- filtres categorie/auteur/date identiques au store `hashmap`
- `limit <= 0` traite comme "sans limite"
- le POC Java appelle ce service via `FaissRemoteBertEmbeddingsStore`

Le moyen le plus simple de le tester est le `docker-compose.yml` fourni (voir section **Integration FAISS avec Docker Compose**).

Configuration manuelle:

```yaml
app:
  embeddings:
    store:
      default: faiss-remote
      faiss:
        enabled: true
        base-url: http://localhost:8090
```

### `qdrant` et `milvus`

Des points d'extension existent deja, mais ces stores ne sont pas encore implementes dans ce repository.

## Feature flags et configuration

Les principaux flips de configuration sont:

```yaml
app:
  indexer:
    default: bert
    use-database: true
  search:
    default: bert
    wildcard: false
    vector:
      max-results: 25
      candidate-multiplier: 4
      min-score: 0.55
    distance:
      enabled: false
      levenshtein: ~2
  embeddings:
    model-id: sentence-transformers/all-MiniLM-L6-v2
    store:
      default: hashmap
      faiss:
        enabled: false
        base-url: http://localhost:8090
    search:
      max-results: 25
      min-score: 0.10
      semantic-weight: 0.75
      lexical-weight: 0.25
      candidate-limit: 0
  parser:
    ocr:
      enabled: true
      default: pdfbox
      tesseract:
        dataPath: C:/Program Files/Tesseract-OCR/tessdata
  storage:
    default: fs
    s3:
      enabled: false
  cipher:
    enabled: true
  task:
    ocr:
      enabled: false
```

### Sens des flags

- `app.indexer.default`: moteur d'indexation principal (`lucene`, `bert` ou `lucene-vector`)
- `app.search.default`: moteur de recherche principal (`lucene`, `bert` ou `lucene-vector`)
- `app.indexer.use-database`: persistance des snapshots d'index en base
- `app.search.vector.max-results`: nombre maximal de resultats du moteur `lucene-vector`
- `app.search.vector.candidate-multiplier`: multiplicateur du nombre de candidats KNN explores par `lucene-vector`
- `app.embeddings.store.default`: implementation du store BERT
- `app.embeddings.store.faiss.enabled`: active le client FAISS distant
- `app.storage.default`: backend de stockage (`fs` ou `s3`)
- `app.storage.s3.enabled`: active le bean S3 (necessite un serveur S3 ou MinIO)
- `app.task.ocr.enabled`: active la tache OCR asynchrone
- `app.search.wildcard`: ajoute un wildcard sur certaines requetes non Lucene
- `app.search.distance.enabled`: active l'extension fuzzy configuree pour les requetes non Lucene
- `app.parser.ocr.tesseract.dataPath`: chemin du repertoire `tessdata`

## Securite

Le projet chiffre:

- les metadonnees documentaires sensibles
- les snapshots Lucene
- les snapshots BERT

Le chiffrement est realise par `CipherService`.

Important:

- les structures de recherche restent dechiffrees en memoire
- ce choix est volontaire pour la performance et la simplicite du POC

## Donnees persistees

- `storage/` contient la base locale et les documents
- `lucene-suggest/` contient l'index d'autocompletion
- H2 contient des snapshots chiffres separes par moteur:
- `lucene_index` pour `lucene`
- `bert_embeddings_index` pour `bert`
- `lucene_vector_index` pour `lucene-vector`

Ce decouplage evite toute confusion quand on change `app.indexer.default` ou `app.search.default` entre plusieurs campagnes de test.

## Stack technique

- Java 25
- Spring Boot 4
- Maven
- H2
- Apache Lucene
- Apache PDFBox
- Apache Tika
- Tesseract
- DJL
- Hugging Face sentence-transformers
- Python 3.11 + FastAPI + FAISS (service `faiss-service/`)
- AWS SDK v2 S3 (compatible MinIO)

## Demarrage local

```bash
mvn install
java -jar ./target/poc-recherche-documentaire-1.0.0-SNAPSHOT.jar
```

Le fichier `application.yml` est le mode POC par defaut sous Windows.
Si Tesseract est installe classiquement, verifier:

```yaml
app:
  parser:
    ocr:
      tesseract:
        dataPath: C:/Program Files/Tesseract-OCR/tessdata
```

Le profil optionnel `local-windows` peut aussi servir a isoler ce chemin si vous
preferez ne pas le laisser dans votre configuration principale.

Acces utiles en mode local:

- UI web: `http://localhost:8080/index.html`
- Swagger: `http://localhost:8080/swagger-ui/index.html`
- H2 console: `http://localhost:8080/h2-console/`
- Health: `http://localhost:8080/actuator/health`
- Prometheus: `http://localhost:8080/actuator/prometheus`

## Docker

### Image Spring Boot seule

```bash
docker build -t poc-recherche-documentaire .
docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=lucene-vector -v ${PWD}/storage:/app/storage -v ${PWD}/lucene-suggest:/app/lucene-suggest poc-recherche-documentaire
```

L'image Docker:

- installe `tesseract-ocr`
- telecharge `fra.traineddata`
- place les donnees dans `/usr/share/tessdata`

Sous Linux/Docker, lancer explicitement un des profils metier:

- `lucene`
- `lucene-vector`
- `bert`

Chacun surcharge `app.parser.ocr.tesseract.dataPath` avec le chemin Linux adapte.

## Integration FAISS avec Docker Compose

Le fichier `docker-compose.yml` demarre les deux services en une commande:

```bash
docker compose up --build
```

Ce qui est lance:

| Service | Port | Description |
|---------|------|-------------|
| `faiss` | 8090 | Service Python FAISS (`faiss-service/`) |
| `app`   | 8080 | Spring Boot en profil `bert` + store `faiss-remote` |

L'application attend que le healthcheck FAISS soit vert avant de demarrer (`depends_on: condition: service_healthy`).

Un volume nomme `djl-cache` persiste le modele `sentence-transformers/all-MiniLM-L6-v2` entre les redemarrages pour eviter un re-telechargement.

Arret:

```bash
docker compose down
```

Acces utiles une fois demarre:

- UI web: `http://localhost:8080/index.html`
- Swagger: `http://localhost:8080/swagger-ui/index.html`
- FAISS stats: `http://localhost:8090/api/faiss/stats`
- FAISS docs API: `http://localhost:8090/docs`

## Stockage S3 / MinIO

Le backend de stockage `s3` permet d'utiliser n'importe quel serveur S3 compatible au lieu du systeme de fichiers local.

**Aucune image Docker custom n'est necessaire.** Le client AWS SDK v2 utilise `endpointOverride` et `forcePathStyleAccess(true)`, ce qui le rend compatible avec toute implementation S3 standard :

| Serveur | Usage |
|---------|-------|
| `minio/minio` | image Docker officielle, ideal en local |
| `localstack/localstack` | alternative locale avec emulation AWS |
| AWS S3 | laisser `endpoint` vide, credentiels IAM |

### Configuration

Dans `application.yml`:

```yaml
app:
  storage:
    default: s3
    s3:
      enabled: true
      endpoint: http://localhost:9000   # vide pour AWS S3 natif
      region: us-east-1
      access-key: minioadmin
      secret-key: minioadmin
      bucket: documents
      cache-path: ./storage/s3-cache
      auto-create-bucket: true
```

### Test local avec MinIO (image officielle)

```bash
docker run -d --name minio \
  -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio server /data --console-address ":9001"
```

- Console MinIO: `http://localhost:9001`
- API S3: `http://localhost:9000`

### Test local avec LocalStack

```bash
docker run -d --name localstack \
  -p 4566:4566 \
  -e SERVICES=s3 \
  localstack/localstack
```

Adapter ensuite la configuration :

```yaml
app:
  storage:
    s3:
      endpoint: http://localhost:4566
      access-key: test
      secret-key: test
```

### Fonctionnement

- les documents uploades sont stockes dans le bucket S3
- un cache local (`s3-cache/`) sert de relais pour les composants qui manipulent des `Path`
- le bucket est cree automatiquement au demarrage si `auto-create-bucket: true`
- les statistiques (`AppStatsService`) refletent uniquement le cache local, pas le bucket complet
- `moveFile` effectue un copy + delete sur S3 puis un move local du cache

## Limites actuelles

- POC oriente demonstration
- pas de multi-tenant
- pas de gestion avancee des droits
- mode `hashmap` non scalable pour gros corpus
- service FAISS entierement en memoire : un redemarrage du conteneur vide l'index (recharger les documents depuis Spring Boot)
- mode `s3` : les statistiques refletent le cache local, pas le bucket complet
- `qdrant` et `milvus` encore en placeholders

## Tests

La suite de tests couvre a present:

- services documentaires
- indexation Lucene, BERT et Lucene vectoriel
- recherche Lucene, BERT et Lucene vectorielle
- factories applicatives principales
- stores BERT `hashmap`, `faiss-remote`, `qdrant`, `milvus`
- stockage S3 (avec S3Client mocke)
- OCR PDFBox
- service de chiffrement

Execution:

```bash
mvn test
```
