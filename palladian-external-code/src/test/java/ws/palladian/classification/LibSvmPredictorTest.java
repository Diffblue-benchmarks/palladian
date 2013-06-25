/**
 * Created on: 18.12.2012 17:51:14
 */
package ws.palladian.classification;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hamcrest.Matchers;
import org.junit.Test;

import ws.palladian.helper.io.FileHelper;
import ws.palladian.helper.io.ResourceHelper;
import ws.palladian.helper.math.ConfusionMatrix;
import ws.palladian.processing.features.FeatureVector;
import ws.palladian.processing.features.NominalFeature;
import ws.palladian.processing.features.NumericFeature;
/**
 * <p>
 * Tests whether the Palladian wrapper for the Libsvm classifier works correctly or not.
 * </p>
 * 
 * @author Klemens Muthmann
 * @version 1.0
 * @since 0.2.0
 */
public class LibSvmPredictorTest {

    @Test
    public void test() {
        List<Instance> instances = new ArrayList<Instance>();
        FeatureVector featureVector1 = new FeatureVector();
        featureVector1.add(new NominalFeature("a", "a"));
        featureVector1.add(new NumericFeature("b", 0.9));
        FeatureVector featureVector2 = new FeatureVector();
        featureVector2.add(new NominalFeature("a", "b"));
        featureVector2.add(new NumericFeature("b", 0.1));
        Instance instance1 = new Instance("A", featureVector1);
        Instance instance2 = new Instance("B", featureVector2);
        instances.add(instance1);
        instances.add(instance2);

        LibSvmPredictor predictor = new LibSvmPredictor(new LinearKernel(1.0d));
        LibSvmModel model = predictor.train(instances);
        assertThat(model, Matchers.is(Matchers.notNullValue()));

        FeatureVector classificationVector = new FeatureVector();
        classificationVector.add(new NominalFeature("a", "a"));
        classificationVector.add(new NumericFeature("b", 0.8));
        CategoryEntries result = predictor.classify(classificationVector, model);
        assertThat(result.getMostLikelyCategory(), Matchers.is("A"));
    }

    /**
     * <p>
     * A test on a dataset from the LibSvm webpage using the same set of parameters. Should achieve a quite high
     * accuracy.
     * </p>
     * 
     * @throws FileNotFoundException If the training data can not be found.
     */
    @Test
    public void testRealDataSet() throws FileNotFoundException {
        List<Instance> instances = readInstances("/train.1");

        LibSvmPredictor predictor = new LibSvmPredictor(new RBFKernel(2.0d, 2.0d));
        LibSvmModel model = predictor.train(instances);

        List<Instance> test = readInstances("/test.1");
        ConfusionMatrix confusionMatrix = new ConfusionMatrix();
        for (Instance instance : test) {
            CategoryEntries result = predictor.classify(instance.getFeatureVector(), model);
            confusionMatrix.add(instance.getTargetClass(), result.getMostLikelyCategory());
        }

        assertThat(confusionMatrix.getAverageAccuracy(false), is(closeTo(0.954, 0.0001)));
        assertThat(confusionMatrix.getAverageRecall(false), is(closeTo(0.954, 0.0001)));
        assertThat(confusionMatrix.getAveragePrecision(false), is(closeTo(0.954, 0.0001)));
        assertThat(confusionMatrix.getAverageF(0.5, false), is(closeTo(0.954, 0.0001)));
    }

    private List<Instance> readInstances(String resource) throws FileNotFoundException {
        File contentFile = ResourceHelper.getResourceFile(resource);
        List<String> lines = FileHelper.readFileToArray(contentFile);
        List<Instance> ret = new ArrayList<Instance>(lines.size());
        Set<String> normalFeaturePathsSet = new HashSet<String>();
        for (String line : lines) {
            String[] elements = line.split("\\s");
            String targetClass = elements[0];
            FeatureVector featureVector = new FeatureVector();
            for (int i = 1; i < elements.length; i++) {
                String[] element = elements[i].split(":");
                String name = element[0];
                normalFeaturePathsSet.add(name);
                Number value = Double.valueOf(element[1]);
                featureVector.add(new NumericFeature(name, value));
            }
            Instance newInstance = new Instance(targetClass, featureVector);
            ret.add(newInstance);
        }
        return ret;
    }

    @Test
    public void testNormalization() throws Exception {
        Normalization normalization = new Normalization();
        normalization.add(new NumericFeature("test", -10.0d));
        normalization.add(new NumericFeature("test", 10.0d));
        normalization.add(new NumericFeature("test", 2));

        double result = normalization.apply(5.0d);
        assertThat(result, is(0.75));
    }

    @Test
    public void testNormalizationWithEqualMinMax() throws Exception {
        Normalization normalization = new Normalization();
        normalization.add(new NumericFeature("test", 0.9d));
        normalization.add(new NumericFeature("test", 0.9d));

        double result = normalization.apply(5.0d);
        assertThat(result, is(4.1));
    }

}
