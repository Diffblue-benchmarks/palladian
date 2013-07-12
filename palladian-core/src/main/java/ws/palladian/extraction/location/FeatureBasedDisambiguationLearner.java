package ws.palladian.extraction.location;

import java.io.File;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ws.palladian.classification.Instance;
import ws.palladian.classification.dt.BaggedDecisionTreeClassifier;
import ws.palladian.classification.dt.BaggedDecisionTreeModel;
import ws.palladian.classification.utils.ClassificationUtils;
import ws.palladian.extraction.location.LocationExtractorUtils.LocationDocument;
import ws.palladian.extraction.location.LocationFeatureExtraction.LocationInstance;
import ws.palladian.extraction.location.persistence.LocationDatabase;
import ws.palladian.helper.collection.CollectionHelper;
import ws.palladian.helper.collection.MultiMap;
import ws.palladian.helper.constants.Language;
import ws.palladian.helper.io.FileHelper;
import ws.palladian.helper.math.MathHelper;
import ws.palladian.persistence.DatabaseManagerFactory;
import ws.palladian.processing.Trainable;
import ws.palladian.processing.features.Annotated;

public class FeatureBasedDisambiguationLearner {

    /** The logger for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(FeatureBasedDisambiguationLearner.class);

    private final BaggedDecisionTreeClassifier classifier = new BaggedDecisionTreeClassifier();

    private final LocationFeatureExtraction featureExtraction = new LocationFeatureExtraction();

    private final EntityPreprocessingTagger tagger = new EntityPreprocessingTagger();

    private final AnnotationFilter filter = new AnnotationFilter();

    private final LocationSource locationSource;

    public FeatureBasedDisambiguationLearner(LocationSource locationSource) {
        Validate.notNull(locationSource, "locationSource must not be null");
        this.locationSource = locationSource;
    }

    public void learn(File datasetDirectory) {
        learn(LocationExtractorUtils.iterateDataset(datasetDirectory));
    }

    public void learn(Iterator<LocationDocument> trainDocuments) {
        Set<Trainable> trainingData = createTrainingData(trainDocuments);
        String baseFileName = String.format("data/temp/location_disambiguation_%s", System.currentTimeMillis());
        ClassificationUtils.writeCsv(trainingData, new File(baseFileName + ".csv"));
        BaggedDecisionTreeModel model = classifier.train(trainingData);
        String modelFileName = baseFileName + ".model";
        FileHelper.serialize(model, modelFileName);
    }

    private Set<Trainable> createTrainingData(Iterator<LocationDocument> trainDocuments) {
        Set<Trainable> trainingData = CollectionHelper.newHashSet();
        while (trainDocuments.hasNext()) {
            LocationDocument trainDocument = trainDocuments.next();
            String text = trainDocument.getText();
            List<LocationAnnotation> trainAnnotations = trainDocument.getAnnotations();

            List<Annotated> taggedEntities = tagger.getAnnotations(text);
            taggedEntities = filter.filter(taggedEntities);
            MultiMap<String, Location> locations = fetchLocations(taggedEntities);

            Set<LocationInstance> instances = featureExtraction.makeInstances(text, taggedEntities, locations);
            Set<Trainable> trainInstances = createTrainData(instances, trainAnnotations);
            trainingData.addAll(trainInstances);
        }
        return trainingData;
    }

    private MultiMap<String, Location> fetchLocations(List<Annotated> annotations) {
        Set<String> valuesToRetrieve = CollectionHelper.newHashSet();
        for (Annotated annotation : annotations) {
            String entityValue = LocationExtractorUtils.normalizeName(annotation.getValue());
            valuesToRetrieve.add(entityValue);
        }
        return locationSource.getLocations(valuesToRetrieve, EnumSet.of(Language.ENGLISH));
    }

    private Set<Trainable> createTrainData(Set<LocationInstance> instances, List<LocationAnnotation> positiveLocations) {
        Set<Trainable> result = CollectionHelper.newHashSet();
        int numPositive = 0;
        for (LocationInstance instance : instances) {
            boolean positiveClass = false;
            for (LocationAnnotation trainAnnotation : positiveLocations) {
                // we cannot determine the correct location, if the training data did not provide coordinates
                if (instance.getLatitude() == null || instance.getLongitude() == null) {
                    continue;
                }
                Location trainLocation = trainAnnotation.getLocation();
                // XXX offsets are not considered here; necessary?
                boolean samePlace = GeoUtils.getDistance(instance, trainLocation) < 50;
                boolean sameName = LocationExtractorUtils.commonName(instance, trainLocation);
                boolean sameType = instance.getType().equals(trainLocation.getType());
                // consider locations as positive samples, if they have same name and have max. distance of 50 kms
                if (samePlace && sameName && sameType) {
                    numPositive++;
                    positiveClass = true;
                    break;
                }
            }
            result.add(new Instance(String.valueOf(positiveClass), instance));
        }
        double positivePercentage = MathHelper.round((float)numPositive / instances.size() * 100, 2);
        LOGGER.info("{} positive instances in {} ({}%)", numPositive, instances.size(), positivePercentage);
        return result;
    }

    public static void main(String[] args) {
        LocationSource locationSource = DatabaseManagerFactory.create(LocationDatabase.class, "locations");
        FeatureBasedDisambiguationLearner learner = new FeatureBasedDisambiguationLearner(locationSource);
        File dataset = new File("/Users/pk/Desktop/TUD-Loc-2013/TUD-Loc-2013_V2/1-training");
        learner.learn(dataset);
    }

}
