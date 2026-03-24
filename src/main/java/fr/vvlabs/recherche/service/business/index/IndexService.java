package fr.vvlabs.recherche.service.business.index;

import fr.vvlabs.recherche.dto.DocumentDTO;

import java.io.IOException;

public interface IndexService<T> {

    String getType();

    void addDocumentToDocumentIndex(DocumentDTO documentDTO, String data) throws IOException;
    void addAuthorToAucompleteIndex(String author);

    T loadDocumentIndexFromDatabase() throws Exception;
    void saveDocumentIndexToDatabase() throws Exception;

    void clearDocumentsIndex() throws IOException;
}

