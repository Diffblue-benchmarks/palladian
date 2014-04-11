package ws.palladian.classification.text;

import static org.junit.Assert.assertEquals;
import static ws.palladian.classification.text.BayesScorer.Options.COMPLEMENT;
import static ws.palladian.classification.text.BayesScorer.Options.PRIORS;

import java.util.List;

import org.junit.Test;

import ws.palladian.core.CategoryEntries;
import ws.palladian.helper.collection.CollectionHelper;

public class PalladianTextClassifierTest {

    private static final FeatureSetting featureSetting = FeatureSettingBuilder.words().create();

    private static final List<ImmutableTextInstance> docs = createDocs();

    private static final String TEST_TEXT = "Chinese Chinese Chinese Tokyo Japan";

    private static List<ImmutableTextInstance> createDocs() {
        // sample data taken from "An Introduction to Information Retrieval";
        // Christopher D. Manning; Prabhakar Raghavan; Hinrich Schütze; 2009, chapter 13 (pp. 253).
        List<ImmutableTextInstance> docs = CollectionHelper.newArrayList();
        docs.add(new ImmutableTextInstance("Chinese Beijing Chinese","yes"));
        docs.add(new ImmutableTextInstance("Chinese Chinese Shanghai","yes"));
        docs.add(new ImmutableTextInstance("Chinese Macao","yes"));
        docs.add(new ImmutableTextInstance("Tokyo Japan Chinese","no"));
        return docs;
    }

    @Test
    public void testPalladianTextClassifier_PalladianScorer() {
        PalladianTextClassifier classifier = new PalladianTextClassifier(featureSetting,
                new PalladianTextClassifier.DefaultScorer());
        DictionaryModel model = classifier.train(docs);
        CategoryEntries result = classifier.classify(TEST_TEXT, model);
        assertEquals("no", result.getMostLikely().getName());
        assertEquals(.79, result.getMostLikely().getProbability(), 0.01);
    }

    @Test
    public void testPalladianTextClassifier_BayesScorer() {
        PalladianTextClassifier classifier = new PalladianTextClassifier(featureSetting, new BayesScorer(PRIORS));
        DictionaryModel model = classifier.train(docs);
        CategoryEntries result = classifier.classify(TEST_TEXT, model);
        assertEquals("yes", result.getMostLikely().getName());
        assertEquals(0.74, result.getMostLikely().getProbability(), 0.01);
    }

    @Test
    public void testPalladianTextClassifier_BayesScorerComplement() {
        PalladianTextClassifier classifier = new PalladianTextClassifier(featureSetting, new BayesScorer(PRIORS,
                COMPLEMENT));
        DictionaryModel model = classifier.train(docs);
        CategoryEntries result = classifier.classify(TEST_TEXT, model);
        assertEquals("yes", result.getMostLikely().getName());
        assertEquals(0.88, result.getMostLikely().getProbability(), 0.01);
    }

}
