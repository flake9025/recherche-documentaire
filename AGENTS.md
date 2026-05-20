# AGENTS.md

## Objectif du repo
- POC Spring Boot de recherche documentaire multi-moteurs (Lucene, BERT, Lucene vector) avec OCR, stockage local/S3/NetApp, snapshots d'index en PostgreSQL, et UI locale (`README.md`).
- Front door API dans `src/main/java/fr/vvlabs/recherche/web/` (`IndexController`, `SearchController`, `DocumentController`, `BulkIndexController`, `AutocompleteController`, `StatsController`).

## Architecture a connaitre en premier
- Le pipeline d'indexation est selectionne via `app.indexer.default` et resolu par `IndexServiceFactory` (`service/index/IndexServiceFactory.java`).
- Le pipeline de recherche est independant via `app.search.default` et `SearchServiceFactory` (`service/search/SearchServiceFactory.java`).
- `SearchController` declenche `SearchStoreInitializer.rebuildIfEmpty(...)` avant chaque recherche: si le store est vide, il reindexe tous les documents depuis `DocumentService` avec **l'indexeur par defaut** (`service/search/SearchStoreInitializer.java`).
- Les interfaces et factories OCR restent dans `core` (`OCRService`, `OCRServiceFactory`, `OCRType`), mais les implementations sont extraites en modules Maven dedies.
- Les metadonnees sont chiffrees/dechiffrees dans `DocumentMapper` via `CipherService` (colonnes `titre_document`, `auteur_depot`, `categories_ens`, `nom_fichier`).
- Les snapshots d'index sont persistes separement: `lucene_index`, `bert_embeddings_index`, `lucene_vector_index` (`src/main/resources/schema.sql`).

## Moteurs et stores
- `lucene`: index texte in-memory `ByteBuffersDirectory` + sauvegarde BLOB (`LuceneIndexService`).
- `bert`: embeddings DJL + reranking lexical metier (`BertEmbeddingsSearchService`), store selectionne par `BertEmbeddingsStoreFactory`.
- `lucene-vector`: `KnnFloatVectorField` natif Lucene (`LuceneVectorIndexService`/`LuceneVectorSearchService`).
- Stores BERT actifs:
  - `hashmap` en memoire (`store/hashmap/HashMapBertEmbeddingsStore.java`)
  - `faiss-remote` via HTTP (`store/faiss/FaissRemoteBertEmbeddingsStore.java`) vers `faiss-service/app.py`
  - `qdrant` via HTTP REST (`store/qdrant/QdrantBertEmbeddingsStore.java`) vers un serveur Qdrant officiel
  - `milvus` via HTTP REST v2 (`store/milvus/MilvusBertEmbeddingsStore.java`) vers un serveur Milvus officiel

## Config/profils qui changent le comportement
- Base locale: `src/main/resources/application.yml` (par defaut `lucene-vector` + OCR active + storage `fs`).
- Profils Docker/Linux: `application-lucene.yml`, `application-bert.yml`, `application-lucene-vector.yml`, `application-milvus.yml` (forcent `tesseract.dataPath=/usr/share/tessdata`).
- Les parsers OCR sont separes en modules Maven `recherche-documentaire-parser-tesseract`, `recherche-documentaire-parser-pdfbox` et `recherche-documentaire-parser-tika`, mais restent embarques ensemble dans la webapp; les beans sont selectionnes via `app.parser.ocr.default` + `app.parser.ocr.enabled`.
- Les backends de stockage sont separes en modules Maven `recherche-documentaire-storage-fs`, `recherche-documentaire-storage-s3` et `recherche-documentaire-storage-netapp`, mais restent embarques ensemble dans la webapp; les beans `s3` et `netapp` sont conditionnels (`app.storage.s3.enabled=true`, `app.storage.netapp.enabled=true`), sinon `FSStorageService` via `recherche-documentaire-storage-fs`.
- La tache OCR asynchrone est desactivee par defaut (`app.task.ocr.enabled=false`) et executee par `OCRIndexTask` + virtual threads (`config/AsyncConfig.java`).

## Workflows dev fiables
- Build + tests: `mvn clean test`
- Build jar: `mvn -DskipTests package`
- Run local: `java -jar target/poc-recherche-documentaire-1.0.0-SNAPSHOT.jar`
- Run compose (app bert + faiss): `docker compose up --build`
- Run compose Qdrant: `docker compose -f docker-compose.qdrant.yml up --build`
- Run compose Milvus: `docker compose -f docker-compose.milvus.yml up --build`
- Arret compose: `docker compose down`
- CI GitHub: `/.github/workflows/build.yml` valide `lucene`, `faiss`, `qdrant` et `milvus` par smoke tests Docker/Compose avant publication

## Conventions de code observees
- Selection de strategie par `getType()` + factories + `@ConditionalOnProperty` (pattern central du projet).
- Les services d'index/search implementent des interfaces communes (`IndexService`, `SearchService`) et publient des types string (`lucene`, `bert`, `lucene-vector`).
- Les parsers OCR publient aussi des types string (`pdfbox`, `tika`, `tesseract`) resolus par `OCRServiceFactory`.
- Les storages documentaires publient aussi des types string (`fs`, `s3`, `netapp`) resolus par `StorageServiceFactory`.
- Les resultats exposent toujours `fileUrl` au format `/api/documents/{id}/file` (voir services de recherche).
- Dates indexees/restituees au format `dd/MM/yyyy HH:mm:ss` dans les indexes Lucene.

## Integration externe
- FAISS distant: API FastAPI `faiss-service/app.py` sur `:8090`, endpoints `/api/faiss/*`, healthcheck sur `/api/faiss/stats`.
- Qdrant: serveur officiel sur `:6333`, endpoint de sante pratique via `/collections` (`docker-compose.qdrant.yml`).
- Milvus: stack officielle standalone (`etcd` + `minio` + `milvus`) sur `:19530` / `:9091`, endpoint de sante pratique via `/healthz` (`docker-compose.milvus.yml`).
- Observabilite: Actuator + Prometheus actifs (`/actuator/health`, `/actuator/prometheus`), metriques de recherche dans `SearchMetricsRecorder`.
- Scripts ops utiles: `deploy/benchmark-upload.sh` (charge mono/bulk contre 3 cibles), `deploy/deploy-github-documents.sh` (deploiement NAS multi-instance + conteneurs FAISS et Qdrant).

## Points d'attention pour agents
- Ne pas supposer que moteur d'indexation == moteur de recherche: les deux sont decouples par config.
- En mode `faiss-remote`, activer **a la fois** `app.embeddings.store.default=faiss-remote` et `app.embeddings.store.faiss.enabled=true`.
- `storage/` et `lucene-suggest/` contiennent de l'etat local; les supprimer reset les donnees de demo.









