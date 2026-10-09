import concurrent.futures
import unittest

from app import (
    DocumentModel,
    SearchRequest,
    clear_documents,
    delete_document,
    get_all_documents,
    replace_documents,
    search,
    stats,
    upsert_document,
)


class FaissStoreTest(unittest.TestCase):
    def setUp(self):
        clear_documents()

    def tearDown(self):
        clear_documents()

    def test_allowed_documents_are_filtered_before_top_k(self):
        upsert_document(DocumentModel(documentId=1, embedding=[1.0, 0.0]))
        upsert_document(DocumentModel(documentId=2, embedding=[0.1, 1.0]))

        result = search(SearchRequest(queryVector=[1.0, 0.0], allowedDocumentIds=[2], limit=1))

        self.assertEqual([2], [match.document.documentId for match in result.matches])

    def test_empty_scope_returns_no_documents(self):
        upsert_document(DocumentModel(documentId=1, embedding=[1.0, 0.0]))

        result = search(SearchRequest(queryVector=[1.0, 0.0], allowedDocumentIds=[], limit=1))

        self.assertEqual([], result.matches)

    def test_unscoped_search_uses_native_faiss_cosine_ranking(self):
        upsert_document(DocumentModel(documentId=1, embedding=[20.0, 0.0]))
        upsert_document(DocumentModel(documentId=2, embedding=[0.0, 1.0]))

        result = search(SearchRequest(queryVector=[2.0, 0.0], limit=2))

        self.assertEqual([1, 2], [match.document.documentId for match in result.matches])
        self.assertAlmostEqual(1.0, result.matches[0].semanticScore, places=6)
        self.assertAlmostEqual(0.0, result.matches[1].semanticScore, places=6)
        self.assertIsInstance(result.model_dump()["matches"][0]["semanticScore"], float)

    def test_scalar_filters_and_scope_are_combined(self):
        replace_documents([
            DocumentModel(documentId=1, embedding=[1.0, 0.0], author="Alice", category="rapport",
                          depotDateTime="2026-10-01T10:00:00"),
            DocumentModel(documentId=2, embedding=[1.0, 0.0], author="Bob", category="rapport",
                          depotDateTime="2026-10-02T10:00:00"),
            DocumentModel(documentId=3, embedding=[1.0, 0.0], author="Alice", category="note",
                          depotDateTime="2026-10-01T10:00:00"),
        ])

        result = search(SearchRequest(
            queryVector=[1.0, 0.0], allowedDocumentIds=[1, 2, 3],
            author=" alice ", category="RAPPORT", dateFrom="2026-10-01", dateTo="2026-10-01",
        ))

        self.assertEqual([1], [match.document.documentId for match in result.matches])

    def test_delete_removes_all_document_chunks(self):
        replace_documents([
            DocumentModel(documentId=1, chunkIndex=0, chunkCount=2, embedding=[1.0, 0.0]),
            DocumentModel(documentId=1, chunkIndex=1, chunkCount=2, embedding=[0.0, 1.0]),
            DocumentModel(documentId=2, embedding=[1.0, 0.0]),
        ])

        delete_document(1)

        self.assertEqual(1, stats().count)
        self.assertEqual([2], [document.documentId for document in get_all_documents()])

    def test_snapshot_round_trip_preserves_chunks_and_vectors(self):
        documents = [
            DocumentModel(documentId=1, chunkIndex=index, chunkCount=2, embedding=[1.0, float(index)],
                          title="Rapport", contentText=f"Passage {index}")
            for index in range(2)
        ]
        replace_documents(documents)
        snapshot = [document.model_dump() for document in get_all_documents()]
        clear_documents()

        replace_documents([DocumentModel.model_validate(document) for document in snapshot])

        self.assertEqual(snapshot, [document.model_dump() for document in get_all_documents()])
        self.assertEqual(2, stats().count)

    def test_concurrent_upserts_preserve_every_document(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
            list(executor.map(
                lambda document_id: upsert_document(
                    DocumentModel(documentId=document_id, embedding=[1.0, 0.0])),
                range(1, 17),
            ))

        self.assertEqual(set(range(1, 17)), {document.documentId for document in get_all_documents()})
        self.assertEqual(16, stats().count)


if __name__ == "__main__":
    unittest.main()
