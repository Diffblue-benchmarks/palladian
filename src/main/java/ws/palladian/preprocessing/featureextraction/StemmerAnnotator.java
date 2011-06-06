package ws.palladian.preprocessing.featureextraction;

import java.util.List;

import org.tartarus.snowball.SnowballStemmer;
import org.tartarus.snowball.ext.englishStemmer;

import ws.palladian.model.features.FeatureVector;
import ws.palladian.model.features.NominalFeature;
import ws.palladian.preprocessing.PipelineDocument;
import ws.palladian.preprocessing.PipelineProcessor;

public class StemmerAnnotator implements PipelineProcessor {

    private static final long serialVersionUID = 1L;
    public static final String PROVIDED_FEATURE = "ws.palladian.features.stem";
    private SnowballStemmer stemmer;
    
    public StemmerAnnotator() {
        this(new englishStemmer());
    }
    
    public StemmerAnnotator(SnowballStemmer stemmer) {
        this.stemmer = stemmer;
    }

    @Override
    public void process(PipelineDocument document) {
        FeatureVector featureVector = document.getFeatureVector();
        AnnotationFeature annotationFeature = (AnnotationFeature) featureVector.get(Tokenizer.PROVIDED_FEATURE);
        if (annotationFeature == null) {
            throw new RuntimeException();
        }
        List<Annotation> annotations = annotationFeature.getValue();
        for (Annotation annotation : annotations) {
            String stem = stem(annotation);
            NominalFeature stemFeature = new NominalFeature(PROVIDED_FEATURE, stem);
            annotation.getFeatureVector().add(stemFeature);
        }
    }

    private String stem(Annotation annotation) {
        stemmer.setCurrent(annotation.getValue());
        stemmer.stem();
        String stem = stemmer.getCurrent();
        return stem;
    }

}
