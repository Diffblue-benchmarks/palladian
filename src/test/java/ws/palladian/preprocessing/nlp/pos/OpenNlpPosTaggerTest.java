package ws.palladian.preprocessing.nlp.pos;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import ws.palladian.helper.io.ResourceHelper;
import ws.palladian.preprocessing.PipelineDocument;
import ws.palladian.preprocessing.ProcessingPipeline;
import ws.palladian.preprocessing.featureextraction.Annotation;
import ws.palladian.preprocessing.featureextraction.AnnotationFeature;
import ws.palladian.preprocessing.nlp.tokenization.RegExTokenizer;
import ws.palladian.preprocessing.nlp.tokenization.Tokenizer;

public class OpenNlpPosTaggerTest {

    private final PipelineDocument document = new PipelineDocument("The quick brown fox jumps over the lazy dog.");
    private File modelFile;

    @Before
    public void setUp() throws FileNotFoundException {
        modelFile = ResourceHelper.getResourceFile("/model/en-pos-maxent.bin");
    }

    @Test
    public void testOpenNlpPosTagger() {
        ProcessingPipeline processingPipeline = new ProcessingPipeline();
        processingPipeline.add(new RegExTokenizer());
        processingPipeline.add(new OpenNlpPosTagger(modelFile));
        processingPipeline.process(document);

        AnnotationFeature annotationFeature = document.getFeatureVector().get(Tokenizer.PROVIDED_FEATURE_DESCRIPTOR);
        List<Annotation> annotations = annotationFeature.getValue();
        
        assertEquals(10, annotations.size());
        assertEquals("DT", annotations.get(0).getFeatureVector().get(BasePosTagger.PROVIDED_FEATURE_DESCRIPTOR).getValue());
        assertEquals("JJ", annotations.get(1).getFeatureVector().get(BasePosTagger.PROVIDED_FEATURE_DESCRIPTOR).getValue());
        assertEquals("JJ", annotations.get(2).getFeatureVector().get(BasePosTagger.PROVIDED_FEATURE_DESCRIPTOR).getValue());
        assertEquals("NN", annotations.get(3).getFeatureVector().get(BasePosTagger.PROVIDED_FEATURE_DESCRIPTOR).getValue());
        assertEquals("NNS", annotations.get(4).getFeatureVector().get(BasePosTagger.PROVIDED_FEATURE_DESCRIPTOR).getValue());
        assertEquals("IN", annotations.get(5).getFeatureVector().get(BasePosTagger.PROVIDED_FEATURE_DESCRIPTOR).getValue());
        assertEquals("DT", annotations.get(6).getFeatureVector().get(BasePosTagger.PROVIDED_FEATURE_DESCRIPTOR).getValue());
        assertEquals("JJ", annotations.get(7).getFeatureVector().get(BasePosTagger.PROVIDED_FEATURE_DESCRIPTOR).getValue());
        assertEquals("NN", annotations.get(8).getFeatureVector().get(BasePosTagger.PROVIDED_FEATURE_DESCRIPTOR).getValue());
        assertEquals(".", annotations.get(9).getFeatureVector().get(BasePosTagger.PROVIDED_FEATURE_DESCRIPTOR).getValue());
    }

}