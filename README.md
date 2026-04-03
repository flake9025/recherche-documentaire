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

### Trois pipelines de recherche

Le projet separe maintenant explicitement:

- `app.indexer.default`
- `app.search.default`

Cela permet de choisir distinctement le moteur utilise pour l'indexation et celui utilise pour la recherche, meme si dans la configuration courante les deux pointent vers `bert`.

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

Le projet a ete prepare pour plusieurs implementations de store vectoriel:

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

Mode d'integration prevu pour un service externe FAISS:

- le POC Java appelle un service distant via HTTP
- ce service n'est pas encore livre dans ce repository
- il pourra etre fourni plus tard sous forme d'image Docker Python/C++

Configuration:

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
      min-score: 0.35
      semantic-weight: 0.75
      lexical-weight: 0.25
      candidate-limit: 0
  parser:
    ocr:
      enabled: true
      default: pdfbox
  storage:
    default: fs
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
- `app.embeddings.store.faiss.enabled`: active le client du futur service FAISS distant
- `app.task.ocr.enabled`: active la tache OCR asynchrone
- `app.search.wildcard`: ajoute un wildcard sur certaines requetes non Lucene
- `app.search.distance.enabled`: active l'extension fuzzy configuree pour les requetes non Lucene

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

## Demarrage local

```bash
mvn install
java -jar ./target/poc-recherche-documentaire-1.0.0-SNAPSHOT.jar
```

Acces utiles:

- UI web: `http://localhost:8080/index.html`
- Swagger: `http://localhost:8080/swagger-ui/index.html`
- H2 console: `http://localhost:8080/h2-console/`

## Docker

```bash
docker build -t poc-recherche-documentaire .
docker run --rm -p 8080:8080 -v ${PWD}/storage:/app/storage -v ${PWD}/lucene-suggest:/app/lucene-suggest poc-recherche-documentaire
```

## Limites actuelles

- POC oriente demonstration
- pas de multi-tenant
- pas de gestion avancee des droits
- mode `hashmap` non scalable pour gros corpus
- `faiss-remote` prepare cote Java mais service externe non encore livre
- `qdrant` et `milvus` encore en placeholders

## Tests

La suite de tests couvre a present:

- services documentaires
- indexation Lucene, BERT et Lucene vectoriel
- recherche Lucene, BERT et Lucene vectorielle
- factories applicatives principales
- stores BERT `hashmap`, `faiss-remote`, `qdrant`, `milvus`
- OCR PDFBox
- service de chiffrement

Execution:

```bash
mvn test
```
