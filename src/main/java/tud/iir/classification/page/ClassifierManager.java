package tud.iir.classification.page;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map.Entry;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.log4j.Logger;

import tud.iir.classification.Categories;
import tud.iir.classification.Category;
import tud.iir.classification.CategoryEntries;
import tud.iir.classification.CategoryEntry;
import tud.iir.classification.Dictionary;
import tud.iir.classification.Term;
import tud.iir.classification.page.evaluation.ClassificationTypeSetting;
import tud.iir.classification.page.evaluation.ClassifierPerformance;
import tud.iir.classification.page.evaluation.CrossValidationResult;
import tud.iir.classification.page.evaluation.CrossValidator;
import tud.iir.classification.page.evaluation.Dataset;
import tud.iir.classification.page.evaluation.EvaluationSetting;
import tud.iir.classification.page.evaluation.FeatureSetting;
import tud.iir.helper.DateHelper;
import tud.iir.helper.FileHelper;
import tud.iir.helper.LineAction;
import tud.iir.helper.MathHelper;
import tud.iir.helper.StopWatch;
import tud.iir.helper.StringHelper;
import tud.iir.helper.TreeNode;
import tud.iir.web.Crawler;
import tud.iir.web.SourceRetriever;
import tud.iir.web.SourceRetrieverManager;

/**
 * This class loads the training and test data, classifies and stores the results.
 * 
 * @author David Urbansky
 */
public class ClassifierManager {

    /** The logger for this class. */
    private static final Logger LOGGER = Logger.getLogger(ClassifierManager.class);

    /** The configuration must be located in config/classification.conf */
    private static PropertiesConfiguration config = null;

    /** The classifier used to categorize the web sites. */
    private TextClassifier classifier = null;

    /** Percentage of pages used as training data. */
    private int trainingDataPercentage = 20;

    /** If true, a preprocessed document will be added to the dictionary right away, that saves memory. */
    private boolean createDictionaryIteratively = true;

    /** Decide whether to index the dictionary in lucene or database. */
    private int dictionaryClassifierIndexType = Dictionary.DB_INDEX_FAST;

    /** Decide whether to use mysql or h2 if index type is database. */
    private int dictionaryDatabaseType = Dictionary.DB_MYSQL;

    /** If true, all n-grams will be searched once before inserting in the db, which saves look up time. */
    private boolean createDictionaryNGramSearchMode = true;

    /** The list of training URLs. */
    private URLs trainingUrls = new URLs();

    /** The list of test URLs. */
    private URLs testUrls = new URLs();

    // // classification modes
    // /** train model, serialize model and use serialized model for test */
    // public static int CLASSIFICATION_TRAIN_TEST_SERIALIZE = 1;
    //
    // /** train model and used trained model for testing without serializing it */
    // public static int CLASSIFICATION_TRAIN_TEST_VOLATILE = 2;
    //
    // /** test model without training it again (model has to exist) */
    // public static int CLASSIFICATION_TEST_MODEL = 3;

    // int classificationMode = CLASSIFICATION_TRAIN_TEST_SERIALIZE;

    StopWatch stopWatch;

    public ClassifierManager() {

        // try to find the classification configuration, if it is not present
        // use default values
        try {
            config = new PropertiesConfiguration("config/classification.conf");
            if (config.getInt("page.trainingPercentage") > -1) {
                setTrainingDataPercentage(config.getInt("page.trainingPercentage"));
            }
            createDictionaryIteratively = config.getBoolean("page.createDictionaryIteratively");
            dictionaryClassifierIndexType = config.getInt("page.dictionaryClassifierIndexType");
            dictionaryDatabaseType = config.getInt("page.databaseType");
            createDictionaryNGramSearchMode = config.getBoolean("page.createDictionaryNGramSearchMode");

        } catch (ConfigurationException e) {
            LOGGER.error(e.getMessage());
        }

    }

    /**
     * Retrieve web pages for a set of categories implying their category.
     */
    public final void learnAndTestClassifierOnline() {

        // category: ["keyword1 keyword2", "keyword4"...]
        HashMap<String, HashSet<String>> dictionary = new HashMap<String, HashSet<String>>();

        // category literature
        HashSet<String> literatureKeywords = new HashSet<String>();
        literatureKeywords.add("\"stephen king\"+buchempfehlung");
        literatureKeywords.add("\"val mcdermid\"+buchempfehlung");
        literatureKeywords.add("\"barbara wood\"+buchempfehlung");
        literatureKeywords.add("buchrezension");
        literatureKeywords.add("buchbesprechung buch");
        literatureKeywords.add("literaturblog");
        literatureKeywords.add("literatur buch");
        dictionary.put("literature", literatureKeywords);

        // category literature
        HashSet<String> filmKeywords = new HashSet<String>();
        filmKeywords.add("\"The Hangover\"+filmkritik");
        filmKeywords.add("\"Cobra\"+filmkritik");
        filmKeywords.add("\"Herz\"+filmkritik");
        filmKeywords.add("\"Liebe\"+filmrezension");
        filmKeywords.add("\"Terminator\"+filmkritik");
        filmKeywords.add("\"Braveheart\"+filmkritik");
        filmKeywords.add("film neuigkeiten");
        filmKeywords.add("film forum guter schauspieler");
        dictionary.put("film", filmKeywords);

        HashSet<String> musicKeywords = new HashSet<String>();
        musicKeywords.add("\"The Beatles\"+album+rezension");
        musicKeywords.add("\"Jimmy Eat World\"+album+kritik");
        musicKeywords.add("\"John Hiatt\"+cd-kritik");
        musicKeywords.add("\"Johnny Cash\"+album+rezension");
        musicKeywords.add("\"Mark Knopfler\"+album+kritik");
        musicKeywords.add("\"Blink-182\"+cd-kritik");
        musicKeywords.add("konzert+live+bericht");
        musicKeywords.add("album+empfehlung+musik");
        dictionary.put("music", musicKeywords);

        // category literature
        HashSet<String> travelKeywords = new HashSet<String>();
        travelKeywords.add("reisebericht");
        travelKeywords.add("reisebericht+urlaub");
        travelKeywords.add("urlaubsempfehlung");
        travelKeywords.add("reise+ratgeber");
        travelKeywords.add("buchbesprechung buch");
        travelKeywords.add("reiseforum+aussicht+urlaub");
        travelKeywords.add("urlaub+blog");
        dictionary.put("travel", travelKeywords);

        // retrieve web pages matching the keywords, download pages and build
        // index
        SourceRetriever sr = new SourceRetriever();
        sr.setSource(SourceRetrieverManager.GOOGLE);
        sr.setResultCount(50);
        sr.setLanguage(SourceRetriever.LANGUAGE_GERMAN);

        Crawler crawler = new Crawler();

        StringBuilder fileIndex = new StringBuilder();
        StringBuilder urlIndex = new StringBuilder();
        int fileCounter = 1;

        System.out.println("Start retrieving web pages...");
        for (Map.Entry<String, HashSet<String>> category : dictionary.entrySet()) {

            for (String keyword : category.getValue()) {
                ArrayList<String> urls = sr.getURLs(keyword);

                for (String url : urls) {
                    String shortURLName = StringHelper.makeSafeName(Crawler.getCleanURL(url));
                    String cleanURLName = "webpage" + fileCounter++ + "_"
                            + shortURLName.substring(0, Math.min(25, shortURLName.length())) + ".html";

                    // download file
                    if (crawler.downloadAndSave(url, "data/benchmarkSelection/page/automatic/" + cleanURLName)) {
                        fileIndex.append(cleanURLName).append(" ").append(category.getKey()).append("\n");
                        urlIndex.append(Crawler.getCleanURL(url)).append(" ").append(category.getKey()).append("\n");
                        System.out.println("Saved and indexed " + url + " to " + cleanURLName);
                    } else {
                        System.out.println("Failed to save from page from " + url);
                    }

                }

            }

        }

        System.out.print("Saving index files...");
        FileHelper.writeToFile("data/benchmarkSelection/page/automatic/4categories_index.txt", fileIndex);
        FileHelper.writeToFile("data/benchmarkSelection/page/url/4categories_urls.txt", urlIndex);
        System.out.println("done");
    }

    // /**
    // * Start training a classifier.
    // *
    // * @deprecated insanely long, use train and test separately
    // *
    // * @filePath The path of the text file with the URLs and categories.
    // * @classifierType The type of the classifier that should be trained.
    // */
    // @Deprecated
    // public final void trainAndTestClassifier(String filePath, int classifierType, int classType, int
    // classificationMode) {
    // long startTime = System.currentTimeMillis();
    // // if (classificationMode != CLASSIFICATION_TEST_MODEL) {
    // // create classifier
    // if (classifierType == 1) {
    // classifier = new URLClassifier();
    // } else if (classifierType == 2) {
    // classifier = new FullPageClassifier();
    // } else if (classifierType == 4) {
    // classifier = new CombinedClassifier();
    // } else if (classifierType == 3) {
    // classifier = new KNNClassifier();
    // }
    // // XXX temp. added by Philipp,
    // // to allow classification of local text contents,
    // // see JavaDoc comments of the class for more information
    // else {
    // classifier = new TextClassifier_old();
    // }
    // // }
    // if (classifierType != 3) {
    // // set index location (lucene or database)
    // ((DictionaryClassifier) classifier).dictionary.setIndexType(dictionaryClassifierIndexType);
    // // set database type
    // ((DictionaryClassifier) classifier).dictionary.setDatabaseType(dictionaryDatabaseType);
    // // set class type
    // ((DictionaryClassifier) classifier).dictionary.setClassType(classType);
    // // if index should be created iteratively, we do not keep it in memory
    // // but write it to disk right away
    // if (createDictionaryIteratively) {
    // ((DictionaryClassifier) classifier).dictionary.useIndex(classType);
    // // in training mode, the dictionary will be deleted first
    // if (classificationMode == CLASSIFICATION_TRAIN_TEST_SERIALIZE) {
    // ((DictionaryClassifier) classifier).dictionary.emptyIndex();
    // }
    // }
    // }
    // // classifier.setBenchmark(true);
    // // read the urls (training and test data) from urls.txt file
    // trainingUrls = new URLs();
    // testUrls = new URLs();
    // Dataset ds = new Dataset();
    // ds.setPath(filePath);
    // readTrainingTestingData(trainingDataPercentage, ds, classType);
    // // load the text data from the gathered urls, preprocess the data and
    // // create document representations
    // if (classificationMode == CLASSIFICATION_TRAIN_TEST_SERIALIZE) {
    // ((DictionaryClassifier) classifier).dictionary.setReadFromIndexForUpdate(!createDictionaryNGramSearchMode);
    // if (createDictionaryNGramSearchMode && createDictionaryIteratively) {
    // preprocessDocumentsFast(classType);
    // } else {
    // preprocessDocuments(classType, createDictionaryIteratively);
    // }
    // } else if (classificationMode == CLASSIFICATION_TEST_MODEL) {
    // preprocessDocuments(classType, false);
    // } else if (classificationMode == CLASSIFICATION_TRAIN_TEST_VOLATILE) {
    // if (classifierType != 3) {
    // preprocessDocuments(classType, true);
    // } else {
    // preprocessDocuments(classType, false);
    // }
    // }
    // LOGGER.info("loaded and preprocessed successfully");
    // // create the dictionary in one single step
    // if (!createDictionaryIteratively && classificationMode == CLASSIFICATION_TRAIN_TEST_SERIALIZE) {
    // ((DictionaryClassifier) classifier).buildDictionary(classType);
    // }
    // // close the dictionary index writer
    // else if (classifierType != 3) {
    // ((DictionaryClassifier) classifier).dictionary.closeIndexWriter();
    // }
    // // in hierarchy mode we have to tell the dictionary which categories are
    // // main categories
    // if (classType == ClassificationTypeSetting.HIERARCHICAL) {
    // ((DictionaryClassifier) classifier).getDictionary().setMainCategories(classifier.categories);
    // }
    // // save the dictionary (serialize, in-memory dictionary will be deleted
    // // at this point)
    // if (classificationMode == CLASSIFICATION_TRAIN_TEST_SERIALIZE) {
    // ((DictionaryClassifier) classifier).saveDictionary(classType, !createDictionaryIteratively, true);
    // }
    // LOGGER.info("start classifying " + testUrls.size() + " documents");
    // if (classifier instanceof DictionaryClassifier) {
    // classifier.setCategories(((DictionaryClassifier) classifier).getCategories());
    // }
    // if (classificationMode == CLASSIFICATION_TRAIN_TEST_VOLATILE) {
    // if (classifier instanceof DictionaryClassifier) {
    // ((DictionaryClassifier) classifier).classifyTestDocuments(false);
    // } else {
    // classifier.classifyTestDocuments();
    // }
    // } else {
    // classifier.classifyTestDocuments();
    // }
    //
    // ClassifierPerformance performance = classifier.getPerformance();
    //
    // // create log document
    // String timeNeeded = DateHelper.getRuntime(startTime);
    // LOGGER.info("Classifier: " + classifier.getName() + " (with parameters: " + classifier.getParameters() + ")");
    // LOGGER.info("Classification type: " + classType);
    // LOGGER.info("Document Representation Settings: " + classifier.getFeatureSetting() + " Weights: Domain "
    // + Preprocessor.WEIGHT_DOMAIN_TERM + " Title " + Preprocessor.WEIGHT_TITLE_TERM + " Keyword "
    // + Preprocessor.WEIGHT_KEYWORD_TERM + " Meta " + Preprocessor.WEIGHT_META_TERM + " Body "
    // + Preprocessor.WEIGHT_BODY_TERM);
    // LOGGER.info("Use " + trainingDataPercentage + "% as training data. Loaded " + trainingUrls.size()
    // + " training urls, " + testUrls.size() + " test urls in " + classifier.categories.size()
    // + " categories");
    // LOGGER.info("Runtime: " + timeNeeded);
    // if (classType != ClassificationTypeSetting.TAG) {
    // LOGGER
    // .info("Category                      Training  Test  Classified  Correct  Precision        Recall           F1               Sensitivity      Specificity      Accuracy         Weight/Prior");
    // int totalCorrect = 0;
    // for (Category category : classifier.categories) {
    // // skip categories that are not main categories because they are
    // // classified according to the main category
    // if (classType == ClassificationTypeSetting.HIERARCHICAL && !category.isMainCategory()) {
    // continue;
    // }
    // StringBuilder logLine = new StringBuilder(category.getName());
    // for (int i = 0, l = logLine.length(); i < Math.max(0, 30 - l); i++) {
    // logLine.append(" ");
    // }
    // logLine.append(classifier.getTrainingDocuments().getRealNumberOfCategory(category));
    // for (int i = 0, l = logLine.length(); i < Math.max(0, 40 - l); i++) {
    // logLine.append(" ");
    // }
    // logLine.append(classifier.getTestDocuments().getRealNumberOfCategory(category));
    // for (int i = 0, l = logLine.length(); i < Math.max(0, 46 - l); i++) {
    // logLine.append(" ");
    // }
    // logLine.append(classifier.getTestDocuments().getClassifiedNumberOfCategory(category));
    // for (int i = 0, l = logLine.length(); i < Math.max(0, 58 - l); i++) {
    // logLine.append(" ");
    // }
    // logLine.append(performance.getNumberOfCorrectClassifiedDocumentsInCategory(category));
    // totalCorrect += performance.getNumberOfCorrectClassifiedDocumentsInCategory(category);
    // for (int i = 0, l = logLine.length(); i < Math.max(0, 67 - l); i++) {
    // logLine.append(" ");
    // }
    // logLine.append((int) Math.floor(100 * performance.getPrecisionForCategory(category)) + "%");
    // for (int i = 0, l = logLine.length(); i < Math.max(0, 84 - l); i++) {
    // logLine.append(" ");
    // }
    // logLine.append((int) Math.floor(100 * performance.getRecallForCategory(category)) + "%");
    // for (int i = 0, l = logLine.length(); i < Math.max(0, 101 - l); i++) {
    // logLine.append(" ");
    // }
    // logLine.append(Math.floor(100 * performance.getFForCategory(category, 0.5)) / 100);
    // for (int i = 0, l = logLine.length(); i < Math.max(0, 118 - l); i++) {
    // logLine.append(" ");
    // }
    // logLine.append((int) Math.floor(100 * performance.getSensitivityForCategory(category)) + "%");
    // for (int i = 0, l = logLine.length(); i < Math.max(0, 135 - l); i++) {
    // logLine.append(" ");
    // }
    // logLine.append((int) Math.floor(100 * performance.getSpecificityForCategory(category)) + "%");
    // for (int i = 0, l = logLine.length(); i < Math.max(0, 152 - l); i++) {
    // logLine.append(" ");
    // }
    // logLine.append(MathHelper.round(performance.getAccuracyForCategory(category), 2));
    // for (int i = 0, l = logLine.length(); i < Math.max(0, 169 - l); i++) {
    // logLine.append(" ");
    // }
    // logLine.append(MathHelper.round(performance.getWeightForCategory(category), 2));
    // LOGGER.info(logLine.toString());
    // }
    // LOGGER.info("Average Precision: " + (int) Math.floor(100 * performance.getAveragePrecision(false))
    // + "%, weighted: " + (int) Math.floor(100 * performance.getAveragePrecision(true)) + "%");
    // LOGGER.info("Average Recall: " + (int) Math.floor(100 * performance.getAverageRecall(false))
    // + "%, weighted: " + (int) Math.floor(100 * performance.getAverageRecall(true)) + "%");
    // LOGGER.info("Average F1: " + Math.floor(1000 * performance.getAverageF(0.5, false)) / 1000 + ", weighted: "
    // + Math.floor(1000 * performance.getAverageF(0.5, true)) / 1000);
    // LOGGER.info("Average Sensitivity: " + (int) Math.floor(100 * performance.getAverageSensitivity(false))
    // + "%, weighted: " + (int) Math.floor(100 * performance.getAverageSensitivity(true)) + "%");
    // LOGGER.info("Average Specificity: " + (int) Math.floor(100 * performance.getAverageSpecificity(false))
    // + "%, weighted: " + (int) Math.floor(100 * performance.getAverageSpecificity(true)) + "%");
    // LOGGER.info("Average Accuracy: " + Math.floor(1000 * performance.getAverageAccuracy(false)) / 1000
    // + ", weighted: " + Math.floor(1000 * performance.getAverageAccuracy(true)) / 1000);
    // if (classType == ClassificationTypeSetting.SINGLE) {
    // double correctClassified = (double) totalCorrect / (double) testUrls.size();
    // LOGGER.info("Correctly Classified: " + MathHelper.round(100 * correctClassified, 2) + "%");
    // }
    // }
    // LOGGER.info("\nClassified Documents in Detail:");
    // LOGGER.info(classifier.showTestDocuments());
    // LOGGER.info("FINISH, classified and logged successfully " + DateHelper.getRuntime(startTime));
    // }

    public final void trainAndTestClassifier(TextClassifier classifier, EvaluationSetting evaluationSetting) {

        CrossValidator cv = new CrossValidator();
        cv.setEvaluationSetting(evaluationSetting);
        cv.crossValidate(classifier);

    }

    public final void trainClassifier(Dataset dataset, TextClassifier classifier) {

        this.classifier = classifier;

        stopWatch = new StopWatch();

        if (!(classifier instanceof KNNClassifier)) {

            // set index location (lucene or database)
            ((DictionaryClassifier) classifier).dictionary.setIndexType(dictionaryClassifierIndexType);

            // set database type
            ((DictionaryClassifier) classifier).dictionary.setDatabaseType(dictionaryDatabaseType);

            // set class type
            ((DictionaryClassifier) classifier).dictionary.setClassType(classifier.getClassificationType());

            // if index should be created iteratively, we do not keep it in memory
            // but write it to disk right away
            if (createDictionaryIteratively) {
                ((DictionaryClassifier) classifier).dictionary.useIndex(classifier.getClassificationType());

                // in training mode, the dictionary will be deleted first
                if (classifier.isSerialize()) {
                    ((DictionaryClassifier) classifier).dictionary.emptyIndex();
                }
            }
        }

        // read the training URLs from the given dataset
        readTrainingTestingData(dataset, true, classifier.getClassificationType());

        // load the text data from the gathered URLs, preprocess the data and create document representations
        if (classifier.isSerialize()) {

            ((DictionaryClassifier) classifier).dictionary.setReadFromIndexForUpdate(!createDictionaryNGramSearchMode);
            if (createDictionaryNGramSearchMode && createDictionaryIteratively) {
                preprocessDocumentsFast(classifier.getClassificationType());
            } else {
                preprocessDocuments(classifier.getClassificationType(), createDictionaryIteratively);
            }

        } else {
            preprocessDocuments(classifier.getClassificationType(), true);
        }

        LOGGER.info("loaded and preprocessed successfully");

        if (classifier instanceof DictionaryClassifier) {

            // create the dictionary in one single step
            if (!createDictionaryIteratively && classifier.isSerialize()) {
                ((DictionaryClassifier) classifier).buildDictionary(classifier.getClassificationType());
            }
            // close the dictionary index writer
            else {
                ((DictionaryClassifier) classifier).dictionary.closeIndexWriter();
            }

        }

        // in hierarchy mode we have to tell the dictionary which categories are main categories
        if (classifier.getClassificationType() == ClassificationTypeSetting.HIERARCHICAL) {
            ((DictionaryClassifier) classifier).getDictionary().setMainCategories(classifier.categories);
        }

        // save the dictionary (serialize, in-memory dictionary will be deleted at this point)
        if (classifier instanceof DictionaryClassifier && classifier.isSerialize()) {
            ((DictionaryClassifier) classifier).saveDictionary(classifier.getClassificationType(),
                    !createDictionaryIteratively, true);
        }

    }

    public void testClassifier(Dataset dataset, TextClassifier classifier) {

        this.classifier = classifier;

        LOGGER.info("start classifying " + testUrls.size() + " documents");

        // read the testing URLs from the given dataset
        readTrainingTestingData(dataset, false, classifier.getClassificationType());

        preprocessDocuments(classifier.getClassificationType(), false);

        if (classifier instanceof DictionaryClassifier) {
            classifier.setCategories(((DictionaryClassifier) classifier).getCategories());
        }

        if (!classifier.isSerialize() && classifier instanceof DictionaryClassifier) {
            ((DictionaryClassifier) classifier).classifyTestDocuments(false);
        } else {
            classifier.classifyTestDocuments();
        }

        writeLog(classifier);
    }

    private void writeLog(TextClassifier classifier) {

        // create log document
        String timeNeeded = stopWatch.getElapsedTimeString();

        ClassifierPerformance performance = classifier.getPerformance();

        LOGGER.info("Classifier: " + classifier.getName() + " (with parameters: " + classifier.getParameters() + ")");
        LOGGER.info("Classification type: " + classifier.getClassificationType());
        LOGGER.info("Document Representation Settings: " + classifier.getFeatureSetting() + " Weights: Domain "
                + Preprocessor.WEIGHT_DOMAIN_TERM + " Title " + Preprocessor.WEIGHT_TITLE_TERM + " Keyword "
                + Preprocessor.WEIGHT_KEYWORD_TERM + " Meta " + Preprocessor.WEIGHT_META_TERM + " Body "
                + Preprocessor.WEIGHT_BODY_TERM);
        LOGGER.info("Use " + trainingDataPercentage + "% as training data. Loaded " + trainingUrls.size()
                + " training urls, " + testUrls.size() + " test urls in " + classifier.categories.size()
                + " categories");
        LOGGER.info("Runtime: " + timeNeeded);

        if (classifier.getClassificationType() != ClassificationTypeSetting.TAG) {

            LOGGER
                    .info("Category                      Training  Test  Classified  Correct  Precision        Recall           F1               Sensitivity      Specificity      Accuracy         Weight/Prior");

            int totalCorrect = 0;
            for (Category category : classifier.categories) {

                // skip categories that are not main categories because they are
                // classified according to the main category
                if (classifier.getClassificationType() == ClassificationTypeSetting.HIERARCHICAL
                        && !category.isMainCategory()) {
                    continue;
                }

                StringBuilder logLine = new StringBuilder(category.getName());
                for (int i = 0, l = logLine.length(); i < Math.max(0, 30 - l); i++) {
                    logLine.append(" ");
                }
                logLine.append(classifier.getTrainingDocuments().getRealNumberOfCategory(category));
                for (int i = 0, l = logLine.length(); i < Math.max(0, 40 - l); i++) {
                    logLine.append(" ");
                }
                logLine.append(classifier.getTestDocuments().getRealNumberOfCategory(category));
                for (int i = 0, l = logLine.length(); i < Math.max(0, 46 - l); i++) {
                    logLine.append(" ");
                }
                logLine.append(classifier.getTestDocuments().getClassifiedNumberOfCategory(category));
                for (int i = 0, l = logLine.length(); i < Math.max(0, 58 - l); i++) {
                    logLine.append(" ");
                }
                logLine.append(performance.getNumberOfCorrectClassifiedDocumentsInCategory(category));
                totalCorrect += performance.getNumberOfCorrectClassifiedDocumentsInCategory(category);
                for (int i = 0, l = logLine.length(); i < Math.max(0, 67 - l); i++) {
                    logLine.append(" ");
                }
                logLine.append((int) Math.floor(100 * performance.getPrecisionForCategory(category)) + "%");
                for (int i = 0, l = logLine.length(); i < Math.max(0, 84 - l); i++) {
                    logLine.append(" ");
                }
                logLine.append((int) Math.floor(100 * performance.getRecallForCategory(category)) + "%");
                for (int i = 0, l = logLine.length(); i < Math.max(0, 101 - l); i++) {
                    logLine.append(" ");
                }
                logLine.append(Math.floor(100 * performance.getFForCategory(category, 0.5)) / 100);
                for (int i = 0, l = logLine.length(); i < Math.max(0, 118 - l); i++) {
                    logLine.append(" ");
                }
                logLine.append((int) Math.floor(100 * performance.getSensitivityForCategory(category)) + "%");
                for (int i = 0, l = logLine.length(); i < Math.max(0, 135 - l); i++) {
                    logLine.append(" ");
                }
                logLine.append((int) Math.floor(100 * performance.getSpecificityForCategory(category)) + "%");
                for (int i = 0, l = logLine.length(); i < Math.max(0, 152 - l); i++) {
                    logLine.append(" ");
                }
                logLine.append(MathHelper.round(performance.getAccuracyForCategory(category), 2));
                for (int i = 0, l = logLine.length(); i < Math.max(0, 169 - l); i++) {
                    logLine.append(" ");
                }
                logLine.append(MathHelper.round(performance.getWeightForCategory(category), 2));
                LOGGER.info(logLine.toString());
            }
            LOGGER.info("Average Precision: " + (int) Math.floor(100 * performance.getAveragePrecision(false))
                    + "%, weighted: " + (int) Math.floor(100 * performance.getAveragePrecision(true)) + "%");
            LOGGER.info("Average Recall: " + (int) Math.floor(100 * performance.getAverageRecall(false))
                    + "%, weighted: " + (int) Math.floor(100 * performance.getAverageRecall(true)) + "%");
            LOGGER.info("Average F1: " + Math.floor(1000 * performance.getAverageF(0.5, false)) / 1000 + ", weighted: "
                    + Math.floor(1000 * performance.getAverageF(0.5, true)) / 1000);
            LOGGER.info("Average Sensitivity: " + (int) Math.floor(100 * performance.getAverageSensitivity(false))
                    + "%, weighted: " + (int) Math.floor(100 * performance.getAverageSensitivity(true)) + "%");
            LOGGER.info("Average Specificity: " + (int) Math.floor(100 * performance.getAverageSpecificity(false))
                    + "%, weighted: " + (int) Math.floor(100 * performance.getAverageSpecificity(true)) + "%");
            LOGGER.info("Average Accuracy: " + Math.floor(1000 * performance.getAverageAccuracy(false)) / 1000
                    + ", weighted: " + Math.floor(1000 * performance.getAverageAccuracy(true)) / 1000);

            if (classifier.getClassificationType() == ClassificationTypeSetting.SINGLE) {
                double correctClassified = (double) totalCorrect / (double) testUrls.size();
                LOGGER.info("Correctly Classified: " + MathHelper.round(100 * correctClassified, 2) + "%");
            }

        }

        LOGGER.info("\nClassified Documents in Detail:");
        LOGGER.info(classifier.showTestDocuments());

        LOGGER.info("FINISH, classified and logged successfully in " + stopWatch.getElapsedTimeString());
    }

    // /**
    // * Load, read and build the training data. URLs from the text file are separated into test and training URLs and
    // we
    // * create the categories.
    // *
    // * @deprecated
    // * @param trainingPercentage Number in percent of how many documents should be used as training data.
    // */
    // @Deprecated
    // private void readTrainingTestingData(double trainingPercentage, Dataset dataset, int classType) {
    //
    // final Object[] obj = new Object[3];
    // obj[0] = trainingPercentage;
    // obj[1] = classType;
    // obj[2] = dataset;
    //
    // LineAction la = new LineAction(obj) {
    //
    // @Override
    // public void performAction(String line, int lineNumber) {
    //
    // double trainingStep = 100.0 / (Double) obj[0];
    //
    // String[] siteInformation = line.split(((Dataset) obj[2]).getSeparationString());
    //
    // int l = siteInformation.length;
    // if ((Integer) obj[1] == ClassificationTypeSetting.SINGLE) {
    // l = 2;
    // }
    //
    // String[] urlInformation = new String[l];
    // urlInformation[0] = siteInformation[0];
    //
    // String lastCategoryName = "";
    // String lastCategoryPrefix = "";
    // for (int i = 1; i < siteInformation.length; ++i) {
    // String[] categorieNames = siteInformation[i].split("/");
    // if (categorieNames.length == 0) {
    // LOGGER.debug("no category names found for " + line);
    // return;
    // }
    // String categoryName = categorieNames[0];
    //
    // // update hierarchy
    // if ((Integer) obj[1] == ClassificationTypeSetting.HIERARCHICAL) {
    // // category names must be saved with the information
    // // about the preceding node
    // if (lastCategoryName.length() > 0) {
    // categoryName = lastCategoryPrefix + "_" + categoryName;
    // }
    //
    // TreeNode newNode = new TreeNode(categoryName);
    // if (i == 1) {
    // ((DictionaryClassifier) classifier).getDictionary().hierarchyRootNode.addNode(newNode);
    // } else {
    // ((DictionaryClassifier) classifier).getDictionary().hierarchyRootNode.getNode(
    // lastCategoryName).addNode(newNode);
    // }
    // }
    // urlInformation[i] = categoryName;
    //
    // // add category if it does not exist yet
    // if (!classifier.categories.containsCategoryName(categoryName)) {
    // Category cat = new Category(categoryName);
    // if ((Integer) obj[1] == ClassificationTypeSetting.HIERARCHICAL
    // && ((DictionaryClassifier) classifier).getDictionary().hierarchyRootNode.getNode(
    // categoryName).getParent() == ((DictionaryClassifier) classifier)
    // .getDictionary().hierarchyRootNode
    // || (Integer) obj[1] == ClassificationTypeSetting.SINGLE) {
    // cat.setMainCategory(true);
    // }
    // cat.setClassType((Integer) obj[1]);
    // cat.increaseFrequency();
    // classifier.categories.add(cat);
    // } else {
    // classifier.categories.getCategoryByName(categoryName).setClassType((Integer) obj[1]);
    // classifier.categories.getCategoryByName(categoryName).increaseFrequency();
    // }
    //
    // // only take first category in "first" mode
    // if ((Integer) obj[1] == ClassificationTypeSetting.SINGLE) {
    // break;
    // }
    //
    // lastCategoryName = categoryName;
    // lastCategoryPrefix = categorieNames[0];
    // }
    //
    // // add to training urls
    // if (lineNumber % trainingStep < 1) {
    // trainingUrls.add(urlInformation);
    //
    // // add to test urls
    // } else {
    // testUrls.add(urlInformation);
    // }
    //
    // if (lineNumber % 1000 == 0) {
    // log("read another 1000 lines from training/testing file, total: " + lineNumber);
    // }
    //
    // }
    // };
    //
    // FileHelper.performActionOnEveryLine(dataset.getPath(), la);
    //
    // // calculate the prior for all categories
    // // classifier.categories.calculatePriors(trainingUrls.size() +
    // // testUrls.size());
    // classifier.categories.calculatePriors();
    // }

    private void readTrainingTestingData(Dataset dataset, boolean forTraining, int classType) {

        if (forTraining) {
            trainingUrls = new URLs();
        } else {
            testUrls = new URLs();
        }

        final Object[] obj = new Object[3];
        obj[0] = forTraining;
        obj[1] = classType;
        obj[2] = dataset;

        LineAction la = new LineAction(obj) {

            @Override
            public void performAction(String line, int lineNumber) {

                String[] siteInformation = line.split(((Dataset) obj[2]).getSeparationString());

                int l = siteInformation.length;
                if ((Integer) obj[1] == ClassificationTypeSetting.SINGLE) {
                    l = 2;
                }

                String[] urlInformation = new String[l];
                urlInformation[0] = siteInformation[0];

                String lastCategoryName = "";
                String lastCategoryPrefix = "";
                for (int i = 1; i < siteInformation.length; ++i) {
                    String[] categorieNames = siteInformation[i].split("/");
                    if (categorieNames.length == 0) {
                        LOGGER.debug("no category names found for " + line);
                        return;
                    }
                    String categoryName = categorieNames[0];

                    // update hierarchy
                    if ((Integer) obj[1] == ClassificationTypeSetting.HIERARCHICAL) {
                        // category names must be saved with the information
                        // about the preceding node
                        if (lastCategoryName.length() > 0) {
                            categoryName = lastCategoryPrefix + "_" + categoryName;
                        }

                        TreeNode newNode = new TreeNode(categoryName);
                        if (i == 1) {
                            ((DictionaryClassifier) classifier).getDictionary().hierarchyRootNode.addNode(newNode);
                        } else {
                            ((DictionaryClassifier) classifier).getDictionary().hierarchyRootNode.getNode(
                                    lastCategoryName).addNode(newNode);
                        }
                    }
                    urlInformation[i] = categoryName;

                    // add category if it does not exist yet
                    if (!classifier.categories.containsCategoryName(categoryName)) {
                        Category cat = new Category(categoryName);
                        if ((Integer) obj[1] == ClassificationTypeSetting.HIERARCHICAL
                                && ((DictionaryClassifier) classifier).getDictionary().hierarchyRootNode.getNode(
                                        categoryName).getParent() == ((DictionaryClassifier) classifier)
                                        .getDictionary().hierarchyRootNode
                                || (Integer) obj[1] == ClassificationTypeSetting.SINGLE) {
                            cat.setMainCategory(true);
                        }
                        cat.setClassType((Integer) obj[1]);
                        cat.increaseFrequency();
                        classifier.categories.add(cat);
                    } else {
                        classifier.categories.getCategoryByName(categoryName).setClassType((Integer) obj[1]);
                        classifier.categories.getCategoryByName(categoryName).increaseFrequency();
                    }

                    // only take first category in "first" mode
                    if ((Integer) obj[1] == ClassificationTypeSetting.SINGLE) {
                        break;
                    }

                    lastCategoryName = categoryName;
                    lastCategoryPrefix = categorieNames[0];
                }

                // add to training urls
                if ((Boolean) obj[0] == true) {
                    trainingUrls.add(urlInformation);

                } else {
                    // add to test urls
                    testUrls.add(urlInformation);
                }

                if (lineNumber % 1000 == 0) {
                    log("read another 1000 lines from training/testing file, total: " + lineNumber);
                }

            }
        };

        FileHelper.performActionOnEveryLine(dataset.getPath(), la);

        // calculate the prior for all categories classifier.categories.calculatePriors(trainingUrls.size() +
        // testUrls.size());
        classifier.categories.calculatePriors();
    }

    /**
     * Create a document representation of the data read.
     */
    private void preprocessDocuments(int classType, boolean addToDictionary) {

        int size = trainingUrls.size() + testUrls.size();

        for (int i = 0; i < size; ++i) {

            String[] tData;
            ClassificationDocument preprocessedDocument = null;

            boolean isTrainingDocument;
            if (i < trainingUrls.size()) {
                isTrainingDocument = true;
                tData = trainingUrls.get(i);

                // free memory, delete training URL
                // trainingUrls.set(i, empty);

                preprocessedDocument = new ClassificationDocument();
            } else {
                isTrainingDocument = false;
                tData = testUrls.get(i - trainingUrls.size());

                // free memory, delete testing URL
                // testUrls.set(i - trainingUrls.size(), empty);

                preprocessedDocument = new TestDocument();
            }

            String url = tData[0];

            if (!isTrainingDocument) {
                preprocessedDocument = classifier.preprocessDocument(url, preprocessedDocument);
            } else {
                preprocessedDocument = classifier.preprocessDocument(url, preprocessedDocument);
            }

            preprocessedDocument.setUrl(tData[0]);

            Categories categories = new Categories();
            for (int j = 1; j < tData.length; j++) {
                categories.add(new Category(tData[j]));
            }
            if (isTrainingDocument) {
                preprocessedDocument.setDocumentType(ClassificationDocument.TRAINING);
                preprocessedDocument.setRealCategories(categories);
                classifier.getTrainingDocuments().add(preprocessedDocument);

                if (addToDictionary) {
                    ((DictionaryClassifier) classifier).addToDictionary(preprocessedDocument, classType);
                }

            } else {
                preprocessedDocument.setDocumentType(ClassificationDocument.TEST);
                preprocessedDocument.setRealCategories(categories);
                classifier.getTestDocuments().add(preprocessedDocument);
            }
            log(Math.floor(100.0 * i / size) + "% preprocessed: " + tData[0] + ", i:" + i + ", size:" + size);

        }

        // ThreadHelper.sleep(2 * DateHelper.MINUTE_MS);
    }

    private void preprocessDocumentsFast(int classType) {

        // keep all nGrams that have been found already in memory so we don't
        // have to look them up in the db
        HashSet<Integer> nGramFound = new HashSet<Integer>();

        // iterate through all training and test URLs and preprocess them
        int size = trainingUrls.size();

        for (int i = 0; i < size; ++i) {

            LOGGER.info("processed " + MathHelper.round(100 * i / size, 2) + "% of the documents, ngrams: "
                    + nGramFound.size() + ", time: " + DateHelper.getRuntime(classifier.initTime));

            ClassificationDocument preprocessedDocument = null;

            String[] tData = trainingUrls.get(i);
            preprocessedDocument = preprocessDocument(tData, ClassificationDocument.TRAINING);
            classifier.getTrainingDocuments().add(preprocessedDocument);

            // all nGrams of the current URL are saved in the map with their
            // categories and relevances as ngram => [category => relevance]
            HashMap<String, HashMap<String, Double>> temporaryNGramMap = new HashMap<String, HashMap<String, Double>>();

            for (Map.Entry<Term, Double> nGram : preprocessedDocument.getWeightedTerms().entrySet()) {
                if (nGramFound.add(nGram.hashCode())) {
                    HashMap<String, Double> categoryMap = new HashMap<String, Double>();
                    for (Category c : preprocessedDocument.getRealCategories()) {
                        categoryMap.put(c.getName().toLowerCase(), 1.0);
                    }
                    temporaryNGramMap.put(nGram.getKey().getText().toLowerCase(), categoryMap);
                }
            }

            LOGGER.info(temporaryNGramMap.size() + " new ngrams found...look through all following documents");

            if (temporaryNGramMap.isEmpty()) {
                continue;
            }

            // find same nGrams in all following documents
            for (int j = i + 1; j < size; ++j) {
                String[] tData2 = trainingUrls.get(j);
                ClassificationDocument preprocessedDocument2 = preprocessDocument(tData2,
                        ClassificationDocument.TRAINING);

                // add categories and relevances to temporaryNGramMap
                HashMap<String, Double> categoryMap2 = new HashMap<String, Double>();
                for (Category c : preprocessedDocument2.getRealCategories()) {
                    categoryMap2.put(c.getName().toLowerCase(), 1.0);
                }
                for (Map.Entry<Term, Double> nGram : preprocessedDocument2.getWeightedTerms().entrySet()) {

                    // check if nGram also appears in first document (we do not
                    // want to take all possible nGrams just yet because the
                    // memory will
                    // overflow)
                    if (temporaryNGramMap.containsKey(nGram.getKey().getText().toLowerCase())) {

                        // get list of categories that are currently assigned to
                        // the nGram
                        HashMap<String, Double> categoryMapEntry = temporaryNGramMap.get(nGram.getKey().getText()
                                .toLowerCase());

                        // add categories from second document to nGram or
                        // update relevance if category existed already
                        for (Entry<String, Double> categoryMap2Entry : categoryMap2.entrySet()) {

                            if (categoryMapEntry.containsKey(categoryMap2Entry.getKey())) {
                                // update relevance
                                Double relevance = categoryMapEntry.get(categoryMap2Entry.getKey());
                                relevance += 1.0;
                                categoryMapEntry.put(categoryMap2Entry.getKey(), relevance);
                            } else {
                                // add category
                                categoryMapEntry.put(categoryMap2Entry.getKey(), 1.0);
                            }

                        }

                    }

                }

                // give memory free for jth document
                preprocessedDocument2.getWeightedTerms().clear();
            }

            // write temporary nGram map to database, all nGrams in that map
            // should not appear again in the training set and are final entries
            // (no
            // update or check in db necessary)
            for (Entry<String, HashMap<String, Double>> entry : temporaryNGramMap.entrySet()) {
                CategoryEntries ces = new CategoryEntries();
                for (Entry<String, Double> categoryMapEntry : entry.getValue().entrySet()) {
                    CategoryEntry ce = new CategoryEntry(ces, new Category(categoryMapEntry.getKey()), categoryMapEntry
                            .getValue());
                    ces.add(ce);
                }
            }
            ((DictionaryClassifier) classifier).addToDictionary(preprocessedDocument, classType);

            // give memory free for ith document
            preprocessedDocument.getWeightedTerms().clear();
        }

        // preprocess test URLs
        size = trainingUrls.size() + testUrls.size();

        LOGGER.info("start preprocessing test documents");

        for (int i = trainingUrls.size(); i < size; ++i) {
            TestDocument preprocessedDocument = null;
            String[] tData = testUrls.get(i - trainingUrls.size());
            preprocessedDocument = (TestDocument) preprocessDocument(tData, ClassificationDocument.TEST);
            classifier.getTestDocuments().add(preprocessedDocument);
        }

    }

    private ClassificationDocument preprocessDocument(String[] data, int type) {
        ClassificationDocument preprocessedDocument = null;

        if (type == ClassificationDocument.TEST) {
            preprocessedDocument = new TestDocument();
        } else {
            preprocessedDocument = new ClassificationDocument();
        }
        preprocessedDocument = classifier.preprocessDocument(data[0], preprocessedDocument);

        preprocessedDocument.setUrl(data[0]);

        Categories categories = new Categories();
        for (int j = 1; j < data.length; j++) {
            categories.add(new Category(data[j]));
        }

        preprocessedDocument.setRealCategories(categories);
        preprocessedDocument.setDocumentType(type);

        return preprocessedDocument;
    }

    public static void log(String message) {
        System.out.println(message);
    }

    public final int getTrainingDataPercentage() {
        return trainingDataPercentage;
    }

    public final void setTrainingDataPercentage(int trainingDataPercentage) {
        this.trainingDataPercentage = trainingDataPercentage;
    }

    /**
     * This method simplifies the search for the best combination of classifier and feature settings.
     * It automatically learns and evaluates all given combinations.
     * The result will be a ranked list (by F1 score) of the combinations that perform best on the given training/test
     * data.
     * 
     * @param classificationTypeSettings
     * @param featureSettings
     * @param classifiers
     * @param evaluationSetting
     */
    public void learnBestClassifier(List<ClassificationTypeSetting> classificationTypeSettings,
            List<TextClassifier> classifiers, List<FeatureSetting> featureSettings,
            EvaluationSetting evaluationSetting) {

        Set<CrossValidationResult> cvResults = new HashSet<CrossValidationResult>();

        CrossValidator crossValidator = new CrossValidator();
        crossValidator.setEvaluationSetting(evaluationSetting);

        // loop through all classifiers
        for (TextClassifier classifier : classifiers) {

            // loop through all classification types
            for (ClassificationTypeSetting cts : classificationTypeSettings) {

                // loop through all features
                for (FeatureSetting featureSetting : featureSettings) {

                    classifier.setClassificationTypeSetting(cts);
                    classifier.setFeatureSetting(featureSetting);

                    // cross validation
                    CrossValidationResult cvResult = crossValidator.crossValidate(classifier);

                    cvResults.add(cvResult);
                }

            }
        }

        crossValidator.printEvaluationFiles(cvResults, "data/temp");

    }

    /**
     * If arguments are given, they must be in the following order: trainingPercentage inputFilePath classifierType
     * classificationType training For example:
     * java -jar classifierManager.jar 80 data/benchmarkSelection/page/deliciouspages_cleansed_400.txt 1 3 true
     * 
     * @param args
     */
    public static void main(String[] args) {

        // if (args.length > 0) {
        // System.out.println("arguments found");
        // ClassifierManager classifierManager = new ClassifierManager();
        // classifierManager.setTrainingDataPercentage(Integer.parseInt(args[0]));
        // classifierManager.trainAndTestClassifier(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]),
        // Integer.parseInt(args[4]));
        // System.out.println("finished");
        // System.exit(0);
        // }

        // ///////////////////////// test reading from file index // /////////////////////////////
        // DictionaryIndex dictionaryIndex = new
        // DictionaryIndex("data/models/dictionary_URLClassifier_1");
        // dictionaryIndex.openReader();
        // CategoryEntries ces = dictionaryIndex.read(".com/a");
        // System.out.println(ces);
        // System.exit(0);
        // ////////////////////////////////////////////////////////////////////////////////////

        // //////////////////////////// test classification ////////////////////////////////
        //
        // ///////////////////////////////////////////////////////////////////////////////

        // /////////////////////////// learn best classifiers ///////////////////////////////
        ClassifierManager classifierManager = new ClassifierManager();

        // build a set of classification type settings to evaluate
        List<ClassificationTypeSetting> classificationTypeSettings = new ArrayList<ClassificationTypeSetting>();
        ClassificationTypeSetting cts = new ClassificationTypeSetting();
        cts.setClassificationType(ClassificationTypeSetting.SINGLE);
        cts.setSerializeClassifier(false);
        classificationTypeSettings.add(cts);

        // build a set of classifiers to evaluate
        List<TextClassifier> classifiers = new ArrayList<TextClassifier>();
        TextClassifier classifier = null;
        classifier = new DictionaryClassifier();
        classifiers.add(classifier);
        classifier = new KNNClassifier();
        classifiers.add(classifier);

        // build a set of feature settings for evaluation
        List<FeatureSetting> featureSettings = new ArrayList<FeatureSetting>();
        FeatureSetting fs = null;
        fs = new FeatureSetting();
        fs.setTextFeatureType(FeatureSetting.CHAR_NGRAMS);
        fs.setMinNGramLength(4);
        fs.setMaxNGramLength(7);
        featureSettings.add(fs);

        fs = new FeatureSetting();
        fs.setTextFeatureType(FeatureSetting.WORD_NGRAMS);
        fs.setMinNGramLength(1);
        fs.setMaxNGramLength(3);
        featureSettings.add(fs);

        // build a set of datasets that should be used for evaluation
        Set<Dataset> datasets = new TreeSet<Dataset>();
        Dataset dataset = new Dataset();
        dataset.setPath("data/temp/opendirectory_urls_noregional_small.txt");
        datasets.add(dataset);

        // set evaluation settings
        EvaluationSetting evaluationSetting = new EvaluationSetting();
        evaluationSetting.setTrainingPercentageMin(40);
        evaluationSetting.setTrainingPercentageMax(50);
        evaluationSetting.setkFolds(3);
        evaluationSetting.addDataset(dataset);

        // train and test all classifiers in all combinations
        StopWatch stopWatch = new StopWatch();

        // train + test
        classifierManager.learnBestClassifier(classificationTypeSettings, classifiers, featureSettings,
                evaluationSetting);

        System.out.println("finished training and testing classifier in " + stopWatch.getElapsedTimeString());
        System.exit(0);

        // /////////////////////////////////////////////////////////////////////////////////

        // ///////////////////////////// learn classifiers /////////////////////////////////
        classifierManager = new ClassifierManager();
        dataset = new Dataset();
        classifier = new DictionaryClassifier();// new KNNClassifier();
        ClassificationTypeSetting classificationTypeSetting = new ClassificationTypeSetting();
        FeatureSetting featureSetting = new FeatureSetting();
        classifier.setClassificationTypeSetting(classificationTypeSetting);
        classifier.setFeatureSetting(featureSetting);

        // train and test all classifiers
        stopWatch = new StopWatch();

        // train
        // dataset.setPath("data/temp/opendirectory_urls_noregional_small_train.txt");
        // classifierManager.trainClassifier(dataset, classifier);

        // test
        // dataset.setPath("data/temp/opendirectory_urls_noregional_small_test.txt");
        // classifierManager.testClassifier(dataset, classifier);

        // train + test
        evaluationSetting = new EvaluationSetting();
        evaluationSetting.setTrainingPercentageMin(50);
        evaluationSetting.setTrainingPercentageMax(50);
        evaluationSetting.setkFolds(1);
        evaluationSetting.addDataset(dataset);
        dataset.setPath("data/temp/opendirectory_urls_noregional_small.txt");
        classifierManager.trainAndTestClassifier(classifier, evaluationSetting);

        System.out.println("finished training and testing classifier in " + stopWatch.getElapsedTimeString());
        System.exit(0);

    }
}