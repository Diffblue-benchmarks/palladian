package tud.iir.extraction.event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;

import tud.iir.classification.Classifier;
import tud.iir.classification.FeatureObject;
import weka.core.Instance;

/**
 * @author Martin Wunderwald
 * 
 */
public class WhoClassifier extends Classifier {

    private String[] featureNames;

    /** the logger for this class */
    private static final Logger LOGGER = Logger.getLogger(WhoClassifier.class);

    public WhoClassifier(int type) {
        super(type);

        featureNames = new String[4];
        featureNames[0] = "titleEntityCount";
        featureNames[1] = "textEntityCount";
        featureNames[2] = "type";
        featureNames[3] = "distribution";

    }

    /**
     * @param fo
     * @return
     */
    public float classify(FeatureObject fo) {

        Instance iUse = createInstance(getFvWekaAttributes(), discretize(fo
                .getFeatures()), getTrainingSet());

        try {
            double[] fDistribution = getClassifier().distributionForInstance(
                    iUse);

            return (float) fDistribution[0];
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }

    }

    /**
     * Train and save a classifier.
     * 
     * @param path
     * 
     */
    @Override
    public void trainClassifier(String filePath) {
        ArrayList<FeatureObject> fo = readFeatureObjects(filePath);
        setTrainingObjects(fo);
        super.trainClassifier(filePath);

        try {
            weka.core.SerializationHelper.write(
                    "data/learnedClassifiers/who.model", getClassifier());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void testClassifier(String filePath) {
        EventExtractor eventExtractor = EventExtractor.getInstance();
        eventExtractor.setWhoClassifier(getChosenClassifier());
        Event event = EventExtractor
                .extractEventFromURL("http://edition.cnn.com/2010/WORLD/europe/09/28/russia.moscow.mayor/?hpt=T1");

        EventFeatureExtractor.setFeatures(event);
        eventExtractor.extractWho(event);

    }

    /**
     * Use an already trained classifier.
     */
    public void useTrainedClassifier(String filePath) {
        weka.classifiers.Classifier trainedClassifier;
        try {
            trainedClassifier = (weka.classifiers.Classifier) weka.core.SerializationHelper
                    .read(filePath);
            createWekaAttributes(featureNames.length, featureNames);
            setClassifier(trainedClassifier);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void readCorpus() {

        CorpusLoaderReuters clr_test = new CorpusLoaderReuters(1);
        clr_test.loadCorpus();

        LOGGER.info("number of test events:  " + clr_test.getTestSet().size());

        int max = 10;
        EventExtractor eventExtractor = EventExtractor.getInstance();
        useTrainedClassifier("data/learnedClassifiers/who.model");
        eventExtractor.setWhoClassifier(getChosenClassifier());

        for (int i = 0; i < max; i++) {
            Event event = clr_test.getTestSet().get(i);
            EventFeatureExtractor.setFeatures(event);
            eventExtractor.extractWho(event);
        }
    }

    public void collectTrainingData(String filePath) {

        Map<Integer, String[]> events = EventFeatureExtractor
                .readCSV("data/news_articles.csv");

        for (Entry<Integer, String[]> entry : events.entrySet()) {

            HashMap<String, Event> eventMap = new HashMap<String, Event>();

            String[] fields = entry.getValue();
            // int id = entry.getKey();

            String url = fields[0];
            // String title = fields[1];
            String who = fields[2];
            // String where = fields[3];
            // String what = fields[4];
            // String why = fields[5];
            // String how = fields[6];

            /*
             * String query = "";
             * 
             * query += who.replace("|", " "); query += " " + where.replace("|",
             * " ");
             * 
             * LOGGER.info("performing query: " + query);
             * 
             * EventAggregator ea = new EventAggregator(); //
             * ea.setSearchEngine(SourceRetrieverManager.GOOGLE_NEWS);
             * ea.setMaxThreads(5); ea.setResultCount(5); ea.setQuery(query);
             * ea.aggregate();
             */
            eventMap.put(url, EventExtractor.extractEventFromURL(url));

            EventFeatureExtractor.setEntityFeatures(eventMap);

            List<String> whos = new ArrayList<String>();

            if (who.contains("|")) {
                whos = Arrays.asList(who.split("\\|"));
            } else {
                whos.add(who);
            }
            // LOGGER.info(whos);

            EventFeatureExtractor.writeCSV(filePath, eventMap, whos, true);

            // CollectionHelper.print(eventMap);

        }

        // EventFeatureExtractor.writeCSV(filePath, eventMap1, whos, true);
        // EventFeatureExtractor.writeCSV(filePath, eventMap2, whos, true);

    }

    /**
     * @param args
     */
    public static void main(String[] args) {

        WhoClassifier wc = new WhoClassifier(Classifier.LINEAR_REGRESSION);
        // wc.collectTrainingData("data/features/who.csv");
        // wc.trainClassifier("data/features/who.csv");
        wc.readCorpus();

        // wc.useTrainedClassifier("data/learnedClassifiers/who.model");
        // wc.testClassifier("data/learnedClassifiers/who.model");
    }

}
