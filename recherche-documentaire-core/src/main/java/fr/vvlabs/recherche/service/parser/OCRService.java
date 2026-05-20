package fr.vvlabs.recherche.service.parser;

import fr.vvlabs.recherche.config.DataType;

import java.io.IOException;
import java.io.InputStream;

public interface OCRService {

    String getType();

    default String getDocumentDatas(String fileName, InputStream stream, DataType datatype) throws IOException {
        switch (datatype) {
            case DataType.RAPPORT:
                return parseRapport(fileName, stream);
            case DataType.FACTURE:
                return parseFacture(fileName, stream);
            case DataType.CONTRAT:
                return parseContrat(fileName, stream);
            default:
                throw new IOException("Unsupported datatype: " + datatype);
        }
    }

    String parseRapport(String fileName, InputStream stream);
    String parseFacture(String fileName, InputStream stream);
    String parseContrat(String fileName, InputStream stream);
}

