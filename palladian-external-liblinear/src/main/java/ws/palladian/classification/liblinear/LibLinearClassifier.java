package ws.palladian.classification.liblinear;

import java.io.PrintStream;

import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ws.palladian.classification.CategoryEntries;
import ws.palladian.classification.CategoryEntriesBuilder;
import ws.palladian.classification.Classifier;
import ws.palladian.classification.utils.ClassificationUtils;
import ws.palladian.helper.collection.EqualsFilter;
import ws.palladian.helper.collection.Filter;
import ws.palladian.helper.io.Slf4JOutputStream;
import ws.palladian.helper.io.Slf4JOutputStream.Level;
import ws.palladian.processing.Classifiable;
import de.bwaldvogel.liblinear.Linear;

/**
 * <p>
 * Classifier for models created via {@link LibLinearLearner}.
 * </p>
 * 
 * @author Philipp Katz
 */
public final class LibLinearClassifier implements Classifier<LibLinearModel> {

    /** The logger for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(LibLinearClassifier.class);

    static {
        // redirect debug output to logger.
        Linear.setDebugOutput(new PrintStream(new Slf4JOutputStream(LOGGER, Level.DEBUG)));
    }

    @Override
    public CategoryEntries classify(Classifiable classifiable, LibLinearModel model) {
        Validate.notNull(classifiable, "classifiable must not be null");
        Validate.notNull(model, "model must not be null");
        model.getNormalization().normalize(classifiable);
        classifiable = model.getDummyCoder().convert(classifiable);
        classifiable = removeUntrainedFeatures(classifiable, model);
        de.bwaldvogel.liblinear.Feature[] instance = LibLinearLearner.makeInstance(model.getFeatureLabels(),
                classifiable, model.getLLModel().getBias());
        CategoryEntriesBuilder categoryEntriesBuilder = new CategoryEntriesBuilder();
        if (model.getLLModel().isProbabilityModel()) {
            double[] probabilities = new double[model.getCategories().size()];
            Linear.predictProbability(model.getLLModel(), instance, probabilities);
            for (int i = 0; i < probabilities.length; i++) {
                categoryEntriesBuilder.add(model.getCategoryForIndex(i), probabilities[i]);
            }
        } else {
            int classIdx = (int)Linear.predict(model.getLLModel(), instance);
            categoryEntriesBuilder.set(model.getCategories(), 0.);
            categoryEntriesBuilder.add(model.getCategoryForIndex(classIdx), 1.);
        }
        return categoryEntriesBuilder.create();
    }

    /**
     * Remove those features, which we have not trained.
     */
    private Classifiable removeUntrainedFeatures(Classifiable classifiable, LibLinearModel model) {
        int oldSize = classifiable.getFeatureVector().size();
        Filter<String> nameFilter = EqualsFilter.create(model.getFeatureLabels());
        classifiable = ClassificationUtils.filterFeatures(classifiable, nameFilter);
        int numIgnored = oldSize - classifiable.getFeatureVector().size();
        if (numIgnored > 0 && LOGGER.isDebugEnabled()) {
            LOGGER.debug("Ignoring {} unknown features", numIgnored);
        }
        return classifiable;
    }

}