package fr.vvlabs.recherche.service.business.index.embeddings;

import ai.djl.MalformedModelException;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.TextEmbeddingTranslator;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Paths;

public class BertEmbeddingsIndexService {

    public static void main(String[] args) throws IOException, TranslateException, ModelNotFoundException, MalformedModelException  {
        // 1. GÃ©nÃ©rer le vecteur avec DJL
        String text = args[0];
        float[] vector = generateEmbedding(text);

        // 2. Indexer dans Lucene
        indexVector(text, vector);
    }

    /*public static float[] generateEmbedding(String text) throws IOException, TranslateException {
        // Charger le modÃ¨le Sentence-BERT depuis Hugging Face
        String modelUrl = "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2";
        TextEmbeddingTranslator translator = TextEmbeddingTranslator.builder().build();
        try (Model model = Model.newInstance(modelUrl);
             Predictor<String, float[]> predictor = model.newPredictor(translator)) {
            return predictor.predict(text);
        }
    }*/
    public static float[] generateEmbedding(String text)
            throws IOException, TranslateException, ModelNotFoundException, MalformedModelException  {

        String modelId = "sentence-transformers/all-MiniLM-L6-v2";

        HuggingFaceTokenizer tokenizer =
                HuggingFaceTokenizer.newInstance(modelId);

        TextEmbeddingTranslator translator =
                TextEmbeddingTranslator.builder(tokenizer).build();

        Criteria<String, float[]> criteria =
                Criteria.builder()
                        .setTypes(String.class, float[].class)
                        .optModelUrls("djl://ai.djl.huggingface.pytorch/" + modelId)
                        .optTranslator(translator)
                        .build();

        try (ZooModel<String, float[]> model = criteria.loadModel();
             Predictor<String, float[]> predictor = model.newPredictor()) {

            return predictor.predict(text);
        }
    }

    public static void indexVector(String text, float[] vector) throws IOException {
        Directory dir = FSDirectory.open(Paths.get("lucene_index"));
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        try (IndexWriter writer = new IndexWriter(dir, config)) {
            Document doc = new Document();
            doc.add(new TextField("text", text, Field.Store.YES));
            doc.add(new KnnFloatVectorField("vector", vector));
            writer.addDocument(doc);
            writer.commit();
            System.out.println("Vecteur indexÃ© avec succÃ¨s !");
        }
    }
}

