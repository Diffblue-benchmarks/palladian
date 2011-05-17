package ws.palladian.preprocessing.featureextraction;

import java.util.List;

import ws.palladian.model.features.FeatureVector;
import ws.palladian.model.features.NumericFeature;
import ws.palladian.preprocessing.PipelineDocument;
import ws.palladian.preprocessing.PipelineProcessor;

public class TfIdfAnnotator implements PipelineProcessor {
    
    public static final String PROVIDED_FEATURE = "ws.palladian.preprocessing.tokens.tfidf";

    @Override
    public void process(PipelineDocument document) {
        FeatureVector featureVector = document.getFeatureVector();
        TokenFeature tokenFeature = (TokenFeature) featureVector.get(Tokenizer.PROVIDED_FEATURE);
        if (tokenFeature == null) {
            throw new RuntimeException();
        }
        List<Token> tokenList = tokenFeature.getValue();
        for (Token token : tokenList) {
            FeatureVector tokenFeatureVector = token.getFeatureVector();
            double tf = ((NumericFeature) tokenFeatureVector.get(FrequencyCalculator.PROVIDED_FEATURE)).getValue();
            double idf = ((NumericFeature) tokenFeatureVector.get(IdfAnnotator.PROVIDED_FEATURE)).getValue();
            NumericFeature tfidfFeature = new NumericFeature(PROVIDED_FEATURE, tf * idf);
            tokenFeatureVector.add(tfidfFeature);
        }
    }

}