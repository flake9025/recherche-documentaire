"""Service FAISS distant exposant l'API attendue par FaissRemoteBertEmbeddingsStore."""
from __future__ import annotations

import threading
from datetime import date, datetime
from typing import List, Optional

import faiss
import numpy as np
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="FAISS Remote Store", version="1.0.0")

# ---------------------------------------------------------------------------
# État global (in-memory, thread-safe)
# ---------------------------------------------------------------------------
_lock = threading.Lock()
_docs: dict[int, dict] = {}                  # documentId → doc dict
_index: Optional[faiss.IndexFlatIP] = None   # index FAISS courant
_id_map: list[int] = []                      # position FAISS → documentId


# ---------------------------------------------------------------------------
# Modèles Pydantic (miroir des records Java)
# ---------------------------------------------------------------------------
class DocumentModel(BaseModel):
    documentId: int
    title: Optional[str] = None
    author: Optional[str] = None
    category: Optional[str] = None
    filename: Optional[str] = None
    depotDateTime: Optional[str] = None
    contentText: Optional[str] = None
    embedding: List[float]


class SearchRequest(BaseModel):
    queryVector: List[float]
    category: Optional[str] = None
    author: Optional[str] = None
    dateFrom: Optional[str] = None
    dateTo: Optional[str] = None
    limit: int = 0


class SearchMatch(BaseModel):
    document: DocumentModel
    semanticScore: float


class SearchResponse(BaseModel):
    matches: List[SearchMatch]


class StatsResponse(BaseModel):
    count: int


# ---------------------------------------------------------------------------
# Helpers internes
# ---------------------------------------------------------------------------
def _doc_to_model(doc: dict) -> DocumentModel:
    return DocumentModel(
        documentId=doc["documentId"],
        title=doc.get("title"),
        author=doc.get("author"),
        category=doc.get("category"),
        filename=doc.get("filename"),
        depotDateTime=doc.get("depotDateTime"),
        contentText=doc.get("contentText"),
        embedding=doc["embedding"],
    )


def _rebuild_index() -> None:
    """Reconstruit l'index FAISS à partir de _docs. Doit être appelé sous _lock."""
    global _index, _id_map
    if not _docs:
        _index = None
        _id_map = []
        return
    ids = list(_docs.keys())
    vecs = np.array([_docs[i]["embedding"] for i in ids], dtype=np.float32)
    # Normalisation L2 : produit scalaire == similarité cosinus après normalisation.
    norms = np.linalg.norm(vecs, axis=1, keepdims=True)
    norms = np.where(norms == 0, 1.0, norms)
    vecs /= norms
    idx = faiss.IndexFlatIP(vecs.shape[1])
    idx.add(vecs)
    _index = idx
    _id_map = list(ids)


def _matches_text_filter(value: Optional[str], filter_val: Optional[str]) -> bool:
    """Miroir de HashMapBertEmbeddingsStore.matchesTextFilter (equalsIgnoreCase)."""
    if not filter_val or not filter_val.strip():
        return True
    if value is None:
        return False
    return value.casefold() == filter_val.strip().casefold()


def _matches_date_range(depot_dt: Optional[str], date_from: Optional[str], date_to: Optional[str]) -> bool:
    """Miroir de HashMapBertEmbeddingsStore.matchesDateRange."""
    if not date_from and not date_to:
        return True
    if not depot_dt:
        return False
    try:
        doc_date = datetime.fromisoformat(depot_dt).date()
        if date_from and doc_date < date.fromisoformat(date_from):
            return False
        if date_to and doc_date > date.fromisoformat(date_to):
            return False
        return True
    except (ValueError, TypeError):
        return False


def _matches_filters(doc: dict, req: SearchRequest) -> bool:
    return (
        _matches_text_filter(doc.get("category"), req.category)
        and _matches_text_filter(doc.get("author"), req.author)
        and _matches_date_range(doc.get("depotDateTime"), req.dateFrom, req.dateTo)
    )


def _cosine_similarity(q_vec: np.ndarray, doc_embedding: list) -> float:
    """Similitude cosinus robuste — retourne 0.0 si vecteurs invalides."""
    if not doc_embedding or len(doc_embedding) != len(q_vec):
        return 0.0
    v = np.array(doc_embedding, dtype=np.float32)
    v_norm = float(np.linalg.norm(v))
    q_norm = float(np.linalg.norm(q_vec))
    if v_norm == 0.0 or q_norm == 0.0:
        return 0.0
    return float(np.dot(q_vec / q_norm, v / v_norm))


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------
@app.post("/api/faiss/documents")
def upsert_document(doc: DocumentModel) -> dict:
    with _lock:
        _docs[doc.documentId] = doc.model_dump()
        _rebuild_index()
    return {"status": "ok"}


@app.get("/api/faiss/documents", response_model=List[DocumentModel])
def get_all_documents() -> List[DocumentModel]:
    with _lock:
        return [_doc_to_model(d) for d in _docs.values()]


@app.delete("/api/faiss/documents")
def clear_documents() -> dict:
    global _index, _id_map
    with _lock:
        _docs.clear()
        _index = None
        _id_map = []
    return {"status": "ok"}


@app.put("/api/faiss/documents")
def replace_documents(docs: List[DocumentModel]) -> dict:
    with _lock:
        _docs.clear()
        for doc in docs:
            _docs[doc.documentId] = doc.model_dump()
        _rebuild_index()
    return {"status": "ok"}


@app.post("/api/faiss/search", response_model=SearchResponse)
def search(req: SearchRequest) -> SearchResponse:
    with _lock:
        if not _docs or not req.queryVector:
            return SearchResponse(matches=[])

        # limit <= 0 signifie "sans limite" (miroir de HashMapBertEmbeddingsStore)
        effective_limit = req.limit if req.limit > 0 else len(_docs)
        has_filters = any([req.category, req.author, req.dateFrom, req.dateTo])

        if has_filters or _index is None:
            # Calcul brut sur les candidats filtrés
            candidates = [
                (doc_id, doc)
                for doc_id, doc in _docs.items()
                if _matches_filters(doc, req)
            ]
            q = np.array(req.queryVector, dtype=np.float32)
            scored = [
                (doc_id, _cosine_similarity(q, doc["embedding"]))
                for doc_id, doc in candidates
            ]
        else:
            # Recherche ANN rapide via FAISS (sans filtre)
            q = np.array(req.queryVector, dtype=np.float32)
            q_norm_val = float(np.linalg.norm(q))
            q_normalized = q / max(q_norm_val, 1e-10)
            k = min(effective_limit, len(_docs))
            D, I = _index.search(q_normalized.reshape(1, -1), k)
            scored = [
                (int(_id_map[faiss_idx]), float(D[0][j]))
                for j, faiss_idx in enumerate(I[0])
                if faiss_idx >= 0
            ]

        scored.sort(key=lambda x: x[1], reverse=True)
        return SearchResponse(
            matches=[
                SearchMatch(document=_doc_to_model(_docs[doc_id]), semanticScore=score)
                for doc_id, score in scored[:effective_limit]
                if doc_id in _docs
            ]
        )


@app.get("/api/faiss/stats", response_model=StatsResponse)
def stats() -> StatsResponse:
    with _lock:
        return StatsResponse(count=len(_docs))
