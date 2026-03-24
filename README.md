# POC Recherche Documentaire

Proof of concept d'indexation, d'extraction OCR et de recherche documentaire.

## Vue d'ensemble

Ce projet expose trois services REST :

1. Recherche dans un index documentaire Lucene
2. Indexation de documents et de leurs metadonnees
3. Extraction de texte via OCR

Une interface web de demonstration est disponible localement.

![Screenshot](screenshot.png)

## Prerequis

- Java 25+
- Maven 3.9+
- 8 Go de RAM recommandes

## Demarrage

```bash
mvn install
java -jar ./target/poc-recherche-documentaire-1.0.0-SNAPSHOT.jar
```

- UI web: `http://localhost:8080/index.html`
- Swagger: `http://localhost:8080/swagger-ui/index.html`
- Console H2: `http://localhost:8080/h2-console/`

## Formats supportes

| Type de document | Format | Traitement |
| --- | --- | --- |
| Facture | PDF, image | OCR |
| Rapport | PDF, image | OCR |
| Contrat | PDF, image | OCR |

## Donnees generees

Les index d'autocompletion sont stockes localement dans `lucene-suggest`.

Les donnees applicatives sont generees dans `storage/`.

## Nettoyage

```bash
rm -rf lucene-suggest
rm -rf storage/database/*
rm -rf storage/documents/*
```

## Limites

- OCR basique via PDFBox, Tika ou Tesseract
- Pas de gestion des droits
- Usage de demonstration uniquement

## Publication

Le projet a ete neutralise pour une publication publique :

- vocabulaire metier rendu generique
- identifiants projet renommes
- references specifiques retirees de l'UI et de la documentation
