package ws.palladian.preprocessing.nlp.ner.tagger;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import ws.palladian.classification.Category;
import ws.palladian.classification.CategoryEntries;
import ws.palladian.classification.CategoryEntry;
import ws.palladian.classification.Dictionary;
import ws.palladian.classification.Instances;
import ws.palladian.classification.Term;
import ws.palladian.classification.UniversalClassifier;
import ws.palladian.classification.UniversalInstance;
import ws.palladian.classification.page.DictionaryClassifier;
import ws.palladian.classification.page.TextInstance;
import ws.palladian.classification.page.evaluation.ClassificationTypeSetting;
import ws.palladian.helper.FileHelper;
import ws.palladian.helper.LineAction;
import ws.palladian.helper.RegExp;
import ws.palladian.helper.StopWatch;
import ws.palladian.helper.collection.CollectionHelper;
import ws.palladian.helper.collection.CountMap;
import ws.palladian.helper.math.MathHelper;
import ws.palladian.helper.math.Matrix;
import ws.palladian.helper.nlp.StringHelper;
import ws.palladian.helper.nlp.Tokenizer;
import ws.palladian.persistence.DictionaryDbIndexH2;
import ws.palladian.preprocessing.nlp.TagAnnotations;
import ws.palladian.preprocessing.nlp.ner.Annotation;
import ws.palladian.preprocessing.nlp.ner.Annotations;
import ws.palladian.preprocessing.nlp.ner.DateAndTimeTagger;
import ws.palladian.preprocessing.nlp.ner.FileFormatParser;
import ws.palladian.preprocessing.nlp.ner.NamedEntityRecognizer;
import ws.palladian.preprocessing.nlp.ner.StringTagger;
import ws.palladian.preprocessing.nlp.ner.TaggingFormat;
import ws.palladian.preprocessing.nlp.ner.UrlTagger;
import ws.palladian.preprocessing.nlp.ner.dataset.DatasetCreator;
import ws.palladian.preprocessing.nlp.ner.evaluation.EvaluationResult;
import ws.palladian.preprocessing.nlp.pos.LingPipePosTagger;

/**
 * <p>
 * This is the Named Entity Recognizer from Palladian. It is based on rule-based entity delimitation (for English
 * texts), a text classification approach, and analyses the contexts around annotations. The major different to other
 * NERs is that it can be learned on seed entities (just the names) or classically using supervised learning on a tagged
 * dataset.
 * </p>
 * 
 * <p>
 * Palladian NER provides two language modes:
 * <ol>
 * <li>TUDLI => token-based, language independent, that is you can learn any language, the performance is rather poor
 * though. Consider using another recognizer.</li>
 * <li>TUDEng => NED + NEC, English only, this recognizer has shown to reach similar performance on the CoNLL 2003
 * dataset as the state-of-the-art. It works on English texts only.</li>
 * </p>
 * 
 * <p>
 * Palladian NER provides two learning modes:
 * <ol>
 * <li>Complete => you must have a tagged corpus in column format where the first colum is the token and the second
 * column (separated by a tabstop) is the entity type.</li>
 * <li>Sparse => you just need a set of seed entities per concept (the same number per concept is preferred) and you can
 * learn a sparse training file with the {@link DatasetCreator} to learn on. Alternatively you can also learn on the
 * seed entities alone but no context information can be learned which results in a slightly worse performance.</li>
 * </p>
 * 
 * <p>
 * Parameters for performance tuning:
 * <ul>
 * <li>n-gram size of the entity classifier (2-8 seems good)</li>
 * <li>n-gram size of the context classifier (4-6 seems good)</li>
 * <li>window size of the Annotation: {@link Annotation.WINDOW_SIZE}</li>
 * </p>
 * 
 * @author David Urbansky
 * 
 */
public class PalladianNer extends NamedEntityRecognizer implements Serializable {

    /** The logger for this class. */
    private static final Logger LOGGER = Logger.getLogger(PalladianNer.class);

    /** The serial vesion id. */
    private static final long serialVersionUID = -8793232373094322955L;

    private Dictionary entityDictionary = null;

    private Map<String, Term> entityTermMap = new HashMap<String, Term>();

    /** The classifier to use for classifying the annotations. */
    private UniversalClassifier universalClassifier;

    private DictionaryClassifier contextClassifier;

    private CountMap leftContextMap = new CountMap();

    private Matrix patternProbabilityMatrix = new Matrix();

    private Annotations removeAnnotations = new Annotations();

    private Dictionary caseDictionary = null;
    private Map<String, Term> tokenTermMap = new HashMap<String, Term>();

    // learning features
    private boolean removeDates = true;
    private boolean removeDateEntries = true;
    private boolean removeIncorrectlyTaggedInTraining = true;
    private boolean removeWrongEntityBeginnings = false;
    private boolean removeSentenceStartErrorsPos = false;
    private boolean removeSentenceStartErrorsCaseDictionary = false;
    private boolean removeSingleNonNounEntities = false;
    private boolean switchTagAnnotationsUsingPatterns = true;
    private boolean switchTagAnnotationsUsingDictionary = true;
    private boolean unwrapEntities = true;
    private boolean unwrapEntitiesWithContext = true;
    private boolean retraining = true;

    /** Whether the tagger should tag URLs. */
    private boolean tagUrls = true;
    
    /** Whether the tagger should tag dates. */
    private boolean tagDates = true;
    
    /**
     * The language mode, language independent uses more generic regexp to detect entities, while there are more
     * specific ones for English texts.
     * 
     * @author David Urbansky
     * 
     */
    public enum LanguageMode {
        LanguageIndependent, English
    }

    /**
     * The two possible learning modes. Complete requires fully tagged data, sparse needs only some entities tagged in
     * the training file.
     * 
     * @author David Urbansky
     * 
     */
    public enum TrainingMode {
        Complete, Sparse
    }

    /** The language mode. */
    private LanguageMode languageMode = LanguageMode.English;

    /** The training mode. */
    private TrainingMode trainingMode = TrainingMode.Complete;

    // /////////////////// Constructors /////////////////////
    public PalladianNer(LanguageMode languageMode) {
        this.languageMode = languageMode;
        setup();
    }

    public PalladianNer(TrainingMode trainingMode) {
        setTrainingMode(trainingMode);
        setup();
    }

    public PalladianNer(LanguageMode languageMode, TrainingMode trainingMode) {
        this.languageMode = languageMode;
        setTrainingMode(trainingMode);
        setup();
    }

    public PalladianNer() {
        setup();
    }

    // //////////////////////////////////////////////////////

    private void setup() {
        setName("Palladian NER (" + getLanguageMode() + ")");

        universalClassifier = new UniversalClassifier();
        universalClassifier.getTextClassifier().getClassificationTypeSetting()
                .setClassificationType(ClassificationTypeSetting.TAG);

        universalClassifier.getTextClassifier().getDictionary().setName("dictionary");
        // universalClassifier.getTextClassifier().getDictionary().setCaseSensitive(true);
        // the n-gram settings for the entity classifier should be tuned, they do not have a big influence on the size
        // of the model (3-5 to 2-8 => 2MB)
        universalClassifier.getTextClassifier().getFeatureSetting().setMinNGramLength(2);
        universalClassifier.getTextClassifier().getFeatureSetting().setMaxNGramLength(8);

        // use only the text classifier, others have shown to not improve the effectiveness
        universalClassifier.switchClassifiers(true, false, false);

        // hold entities in a dictionary that are learned from the training data
        entityDictionary = new Dictionary("EntityDictionary", ClassificationTypeSetting.SINGLE);
        entityDictionary.setCaseSensitive(true);

        // keep the case dictionary from the training data
        caseDictionary = new Dictionary("CaseDictionary", ClassificationTypeSetting.SINGLE);
        caseDictionary.setCaseSensitive(false);

        // use a context classifier for the left and right context around the annotations
        contextClassifier = new DictionaryClassifier();
        contextClassifier.getClassificationTypeSetting().setClassificationType(ClassificationTypeSetting.TAG);
        contextClassifier.getDictionary().setName("contextDictionary");
        // be careful with the n-gram sizes, they heavily influence the model size
        contextClassifier.getFeatureSetting().setMinNGramLength(4);// 4
        contextClassifier.getFeatureSetting().setMaxNGramLength(5);// 6

        // with entity 2-8 and context 4-7: 173MB model
        // precision MUC: 79.93%, recall MUC: 85.55%, F1 MUC: 82.64%
        // precision exact: 70.66%, recall exact: 75.63%, F1 exact: 73.06%

        // with entity 3-5 and context 4-5: 25MB model
        // with entity 3-5 and context 4-6: 43MB model
        // precision MUC: 74.94%, recall MUC: 80.58%, F1 MUC: 77.66%
        // precision exact: 62.08%, recall exact: 66.76%, F1 exact: 64.34%
        // with entity 2-8 and context 4-6: 45MB model
        // precision MUC: 75.09%, recall MUC: 81.12%, F1 MUC: 77.98%
        // precision exact: 62.39%, recall exact: 67.4%, F1 exact: 64.8%
        // with entity 2-8 and context 2-6: 46MB model
        // precision MUC: 74.71%, recall MUC: 80.71%, F1 MUC: 77.59%
        // precision exact: 61.68%, recall exact: 66.64%, F1 exact: 64.06%
        // with entity 2-8 and context 4-7: 66MB model
        // precision MUC: 75.04%, recall MUC: 81.06%, F1 MUC: 77.93%
        // precision exact: 62.33%, recall exact: 67.33%, F1 exact: 64.73%
        // with entity 2-8 and context 4-5: 29MB model
        // precision MUC: 75.05%, recall MUC: 81.08%, F1 MUC: 77.95%
        // precision exact: 62.36%, recall exact: 67.37%, F1 exact: 64.77%
        // with entity 2-8 and context 4-5, window size 40 was 120 in previous tests: 23MB model
        // precision MUC: 75.17%, recall MUC: 81.2%, F1 MUC: 78.07%
        // precision exact: 62.54%, recall exact: 67.56%, F1 exact: 64.95%
    }

    public static String getModelFileEndingStatic() {
        return "model.gz";
    }
    
    @Override
    public String getModelFileEnding() {
        return getModelFileEndingStatic();
    }

    @Override
    public boolean setsModelFileEndingAutomatically() {
        return false;
    }

    @Override
    public boolean loadModel(String configModelFilePath) {
        StopWatch stopWatch = new StopWatch();

        if (!configModelFilePath.endsWith(getModelFileEnding())) {
            configModelFilePath += "." + getModelFileEnding();
        }

        // set current variables null to save memory otherwise we have those things twice in memory when deserializing
        this.entityDictionary = null;
        this.entityTermMap = null;
        this.caseDictionary = null;
        this.tokenTermMap = null;
        this.universalClassifier = null;
        this.contextClassifier = null;
        this.leftContextMap = null;
        this.patternProbabilityMatrix = null;
        this.removeAnnotations = null;

        PalladianNer n = (PalladianNer) FileHelper.deserialize(configModelFilePath);

        // assign all properties from the loaded model to the current instance
        this.entityDictionary = n.entityDictionary;
        this.entityTermMap = n.entityTermMap;
        this.caseDictionary = n.caseDictionary;
        this.tokenTermMap = n.tokenTermMap;
        this.universalClassifier = n.universalClassifier;
        this.contextClassifier = n.contextClassifier;
        this.leftContextMap = n.leftContextMap;
        this.patternProbabilityMatrix = n.patternProbabilityMatrix;
        this.removeAnnotations = n.removeAnnotations;

        // assign the learning features
        this.removeDates = n.removeDates;
        this.removeDateEntries = n.removeDateEntries;
        this.removeIncorrectlyTaggedInTraining = n.removeIncorrectlyTaggedInTraining;
        this.removeWrongEntityBeginnings = n.removeWrongEntityBeginnings;
        this.removeSentenceStartErrorsPos = n.removeSentenceStartErrorsPos;
        this.removeSentenceStartErrorsCaseDictionary = n.removeSentenceStartErrorsCaseDictionary;
        this.removeSingleNonNounEntities = n.removeSingleNonNounEntities;
        this.switchTagAnnotationsUsingPatterns = n.switchTagAnnotationsUsingPatterns;
        this.switchTagAnnotationsUsingDictionary = n.switchTagAnnotationsUsingDictionary;
        this.unwrapEntities = n.unwrapEntities;
        this.unwrapEntitiesWithContext = n.unwrapEntitiesWithContext;

        setModel(this);

        LOGGER.info("model " + configModelFilePath + " successfully loaded in " + stopWatch.getElapsedTimeString());

        return true;
    }

    /**
     * Load a complete tagger from disk.
     * 
     * @param modelPath The path of the tagger.
     * @return The tagger instance.
     */
    public static PalladianNer load(String modelPath) {
        StopWatch stopWatch = new StopWatch();

        LOGGER.info("deserialzing model from " + modelPath);

        if (!modelPath.endsWith(getModelFileEndingStatic())) {
        	modelPath += "." + getModelFileEndingStatic();
        }
        
        PalladianNer tagger;
        tagger = (PalladianNer) FileHelper.deserialize(modelPath);

        LOGGER.info("loaded tagger successfully in " + stopWatch.getElapsedTimeString());

        return tagger;
    }

    /**
     * Save the tagger to the specified file.
     * 
     * @param modelFilePath The file where the tagger should be saved to. You do not need to add the file ending but if
     *            you do, it should be "model.gz".
     */
    protected void saveModel(String modelFilePath) {

        LOGGER.info("entity dictionary contains " + entityDictionary.size() + " entities");
        entityDictionary.saveAsCSV();

        LOGGER.info("case dictionary contains " + caseDictionary.size() + " entities");
        caseDictionary.saveAsCSV();

        LOGGER.info("serializing Palladian NER to " + modelFilePath);
        if (!modelFilePath.endsWith(getModelFileEnding())) {
            modelFilePath = modelFilePath + "." + getModelFileEnding();
        }
        FileHelper.serialize(this, modelFilePath);

        LOGGER.info("dictionary size: " + universalClassifier.getTextClassifier().getDictionary().size());

        // write model meta information
        LOGGER.info("write model meta information");
        StringBuilder supportedConcepts = new StringBuilder();
        for (Category c : universalClassifier.getTextClassifier().getDictionary().getCategories()) {
            supportedConcepts.append(c.getName()).append("\n");
        }
        FileHelper.writeToFile(FileHelper.getFilePath(modelFilePath) + FileHelper.getFileName(modelFilePath)
                + "_meta.txt", supportedConcepts);

        LOGGER.info("all Palladian NER files written");
    }

    /**
     * Save training entities in a dedicated dictionary.
     * 
     * @param annotation The complete annotation from the training data.
     */
    private void addToEntityDictionary(Annotation annotation) {
        String en = annotation.getEntity();
        Term term = entityTermMap.get(en);
        if (term == null) {
            term = new Term(en);
            entityTermMap.put(en, term);
        }
        entityDictionary.updateWord(term, annotation.getInstanceCategoryName(), 1);
    }

    /**
     * Add a token to the case dictionary.
     * 
     * @param token The token to add.
     */
    private void addToCaseDictionary(String token) {
        token = StringHelper.trim(token);
        if (token.length() < 2) {
            return;
        }
        String caseSignature = StringHelper.getCaseSignature(token);
        if (caseSignature.equals("Aa") || caseSignature.equals("A") || caseSignature.equals("a")) {
            token = token.toLowerCase();
            Term term = tokenTermMap.get(token);
            if (term == null) {
                term = new Term(token);
                tokenTermMap.put(token, term);
            }
            caseDictionary.updateWord(term, caseSignature, 1);
        }
    }

    @Override
    public boolean train(String trainingFilePath, String modelFilePath) {

        if (languageMode.equals(LanguageMode.English)) {
            return trainEnglish(trainingFilePath, modelFilePath);
        } else {
            return trainLanguageIndependent(trainingFilePath, modelFilePath);
        }

    }

    /**
     * <p>Similar to {@link train(String trainingFilePath, String modelFilePath)} method but an additional set of
     * annotations can be given to learn the classifier.</p>
     * 
     * @param trainingFilePath The file of the training file.
     * @param annotations A set of annotations which are used for learning: Improving the text classifier AND adding them to the entity dictionary.
     * @param modelFilePath The path where the model should be saved to.
     * @return <tt>True</tt>, if all training worked, <tt>false</tt> otherwise.
     */
    public boolean train(String trainingFilePath, Annotations annotations, String modelFilePath) {

        // create instances, instances are annotations
        Instances<UniversalInstance> textInstances = new Instances<UniversalInstance>();

        LOGGER.info("start creating " + annotations.size() + " annotations for training");
        for (Annotation annotation : annotations) {
            UniversalInstance textInstance = new UniversalInstance(textInstances);
            textInstance.setTextFeature(annotation.getEntity());
            textInstance.setInstanceCategory(annotation.getInstanceCategory());
            textInstances.add(textInstance);
        }

        // save training entities in a dedicated dictionary
        for (Annotation annotation : annotations) {
            addToEntityDictionary(annotation);
        }

        universalClassifier.getTextClassifier().setTrainingInstances(textInstances);

        return train(trainingFilePath, modelFilePath);
    }

    /**
     * Use only a set of annotations to learn, that is, no training file is required. Use this mostly in the English
     * language mode and do not expect great performance.
     * 
     * @param annotations A set of annotations which are used for learning.
     * @param modelFilePath The path where the model should be saved to.
     * @return <tt>True</tt>, if all training worked, <tt>false</tt> otherwise.
     */
    public boolean train(Annotations annotations, String modelFilePath) {
        return trainLanguageIndependent(annotations, annotations, modelFilePath);
    }

    public boolean trainLanguageIndependent(Annotations annotations, Annotations combinedAnnotations,
            String modelFilePath) {

        // create instances, instances are annotations
        Instances<UniversalInstance> textInstances = new Instances<UniversalInstance>();

        LOGGER.info("start creating " + annotations.size() + " annotations for training");
        for (Annotation annotation : annotations) {
            UniversalInstance textInstance = new UniversalInstance(textInstances);
            textInstance.setTextFeature(annotation.getEntity());
            textInstance.setInstanceCategory(annotation.getInstanceCategory());
            textInstances.add(textInstance);
        }

        // save training entities in a dedicated dictionary
        for (Annotation annotation : combinedAnnotations) {
            addToEntityDictionary(annotation);
        }

        // train the text classifier
        universalClassifier.getTextClassifier().setTrainingInstances(textInstances);

        LOGGER.info("start training classifiers now...");
        universalClassifier.trainAll();

        saveModel(modelFilePath);

        return true;
    }

    /**
     * Train the tagger in language independent mode.
     * 
     * @param trainingFilePath The apther of the training file.
     * @param modelFilePath The path where the model should be saved to.
     * @param additionalTrainingAnnotations Additional annotations that can be used for training.
     * @return <tt>True</tt>, if all training worked, <tt>false</tt> otherwise.
     */
    private boolean trainLanguageIndependent(String trainingFilePath, String modelFilePath,
            Annotations additionalTrainingAnnotations) {

        // get all training annotations including their features
        Annotations annotations = FileFormatParser.getAnnotationsFromColumnTokenBased(trainingFilePath);

        // get annotations combined, e.g. "Phil Simmons", not "Phil" and "Simmons"
        Annotations combinedAnnotations = FileFormatParser.getAnnotationsFromColumn(trainingFilePath);

        // add the additional training annotations, they will be used for the context analysis too
        annotations.addAll(additionalTrainingAnnotations);
        combinedAnnotations.addAll(additionalTrainingAnnotations);

        analyzeContexts(trainingFilePath, annotations);

        return trainLanguageIndependent(annotations, combinedAnnotations, modelFilePath);
    }

    private boolean trainLanguageIndependent(String trainingFilePath, String modelFilePath) {
        return trainLanguageIndependent(trainingFilePath, modelFilePath, new Annotations());
    }

    /**
     * Train the tagger in English mode.
     * 
     * @param trainingFilePath The apther of the training file.
     * @param modelFilePath The path where the model should be saved to.
     * @param additionalTrainingAnnotations Additional annotations that can be used for training.
     * @return <tt>True</tt>, if all training worked, <tt>false</tt> otherwise.
     */
    private boolean trainEnglish(String trainingFilePath, String modelFilePath,
            Annotations additionalTrainingAnnotations) {

        // get all training annotations including their features
        Annotations annotations = FileFormatParser.getAnnotationsFromColumn(trainingFilePath);

        // add the additional training annotations, they will be used for the context analysis too
        annotations.addAll(additionalTrainingAnnotations);

        // create instances with nominal and numeric features
        Instances<UniversalInstance> textInstances = new Instances<UniversalInstance>();

        for (Annotation annotation : annotations) {
            UniversalInstance textInstance = new UniversalInstance(textInstances);
            textInstance.setTextFeature(annotation.getEntity());
            textInstance.setInstanceCategory(annotation.getInstanceCategory());
            textInstances.add(textInstance);

            addToEntityDictionary(annotation);
        }

        // train the text classifier
        universalClassifier.getTextClassifier().addTrainingInstances(textInstances);

        // fill the case dictionary
        List<String> tokens = Tokenizer.tokenize(FileFormatParser.getText(trainingFilePath, TaggingFormat.COLUMN));
        for (String token : tokens) {
            addToCaseDictionary(token);
        }

        // in complete training mode, the tagger is learned twice on the training data
        if (retraining) {
            // //////////////////////////////////////////// wrong entities //////////////////////////////////////
            universalClassifier.trainAll();
            saveModel(modelFilePath);

            removeAnnotations = new Annotations();
            EvaluationResult evaluationResult = evaluate(trainingFilePath, modelFilePath, TaggingFormat.COLUMN);

            // get only those annotations that were incorrectly tagged and were never a real entity that is they have to
            // be in ERROR1 set and NOT in the gold standard
            for (Annotation wrongAnnotation : evaluationResult.getErrorAnnotations().get(EvaluationResult.ERROR1)) {

                // for the numeric classifier it is better if only annotations are removed that never appeared in the
                // gold standard for the text classifier it is better to remove annotations that are just wrong even
                // when they were correct in the gold standard at some point
                boolean addAnnotation = true;

                // check if annotation happens to be in the gold standard, if so, do not declare it completely wrong
                String wrongName = wrongAnnotation.getEntity().toLowerCase();
                for (Annotation gsAnnotation : evaluationResult.getGoldStandardAnnotations()) {
                    if (wrongName.equals(gsAnnotation.getEntity().toLowerCase())) {
                        addAnnotation = false;
                        break;
                    }
                }

                UniversalInstance textInstance = new UniversalInstance(textInstances);
                textInstance.setTextFeature(wrongAnnotation.getEntity());
                textInstance.setInstanceCategory("###NO_ENTITY###");
                textInstances.add(textInstance);

                if (addAnnotation) {
                    removeAnnotations.add(wrongAnnotation);
                }
            }
            System.out.println(removeAnnotations.size() + " annotations need to be completely removed");
            // //////////////////////////////////////////////////////////////////////////////////////////////////
        }

        universalClassifier.getTextClassifier().setTrainingInstances(textInstances);
        universalClassifier.trainAll();

        analyzeContexts(trainingFilePath, annotations);

        saveModel(modelFilePath);

        return true;
    }

    private boolean trainEnglish(String trainingFilePath, String modelFilePath) {
        return trainEnglish(trainingFilePath, modelFilePath, new Annotations());
    }

    /**
     * Classify candidate annotations in English mode.
     * 
     * @param entityCandidates The annotations to be classified.
     * @return Classified annotations.
     */
    private Annotations classifyCandidatesEnglish(Annotations entityCandidates) {
        Annotations annotations = new Annotations();

        int i = 0;
        for (Annotation annotation : entityCandidates) {

            Annotations wrappedAnnotations = new Annotations();

            if (unwrapEntities) {
                wrappedAnnotations = annotation.unwrapAnnotations(annotations, entityDictionary);
            }

            if (!wrappedAnnotations.isEmpty()) {
                for (Annotation annotation2 : wrappedAnnotations) {
                    if (!annotation2.getMostLikelyTagName().equalsIgnoreCase("###NO_ENTITY###")) {
                        annotations.add(annotation2);
                    }
                }
            } else {
                universalClassifier.classify(annotation);
                if (!annotation.getMostLikelyTagName().equalsIgnoreCase("###NO_ENTITY###")) {
                    annotations.add(annotation);
                }
            }

            if (i % 100 == 0) {
                LOGGER.debug("classified " + MathHelper.round(100 * i / entityCandidates.size(), 0) + "%");
            }
            i++;
        }

        return annotations;
    }

    /**
     * Classify candidate annotations in language independent mode.
     * 
     * @param entityCandidates The annotations to be classified.
     * @return Classified annotations.
     */
    private Annotations classifyCandidatesLanguageIndependent(Annotations entityCandidates) {
        Annotations annotations = new Annotations();

        int i = 0;
        for (Annotation annotation : entityCandidates) {

            universalClassifier.classify(annotation);
            if (!annotation.getMostLikelyTagName().equalsIgnoreCase("###NO_ENTITY###")) {
                annotations.add(annotation);
            }

            if (i % 100 == 0) {
                LOGGER.info("classified " + MathHelper.round(100 * i / entityCandidates.size(), 0) + "%");
            }
            i++;
        }

        return annotations;
    }

    @Override
    public Annotations getAnnotations(String inputText) {
        StopWatch stopWatch = new StopWatch();

        Annotations annotations = new Annotations();

        if (languageMode.equals(LanguageMode.English)) {
            annotations = getAnnotationsEnglish(inputText);
        } else {
            annotations = getAnnotationsLanguageIndependent(inputText);
        }
        
        // recognize and add URLs, remove annotations that were part of a URL
        if (isTagUrls()) {
        	UrlTagger urlTagger = new UrlTagger();
        	annotations.addAll(urlTagger.tagUrls(inputText));
        	annotations.removeNestedAnnotations();
        }
        
        // recognize and add dates, remove annotations that were part of a date
        if (isTagDates()) {
        	DateAndTimeTagger datTagger = new DateAndTimeTagger();
        	annotations.addAll(datTagger.tagDateAndTime(inputText));
        	annotations.removeNestedAnnotations();
        }

        FileHelper.writeToFile("data/temp/ner/palladianNerOutput.txt", tagText(inputText, annotations));

        LOGGER.info("got annotations in " + stopWatch.getElapsedTimeString());

        return annotations;
    }

    /**
     * <p>Here all classified annotations are processed again. Depending on the learning settings different actions are
     * performed. These are for example, removing date entries, unwrapping entities, using context patterns to switch
     * annotations or remove possibly incorrect annotations with the case dictionary.</p>
     * 
     * @param annotations The classified annotations to process
     */
    private void postProcessAnnotations(Annotations annotations) {

        LOGGER.info("start post processing annotations");

        StopWatch stopWatch = new StopWatch();

        Annotations toRemove = new Annotations();

        // remove dates
        if (removeDates) {
            stopWatch.start();
            int c = 0;
            for (Annotation annotation : annotations) {
                if (containsDateFragment(annotation.getEntity())) {
                    toRemove.add(annotation);
                    c++;
                }
            }
            LOGGER.info("removed " + c + " purely date annotations in " + stopWatch.getElapsedTimeString());
        }

        // remove date entries in annotations, such as "July Peter Jackson" => "Peter Jackson"
        if (removeDateEntries) {
            stopWatch.start();
            int c = 0;
            for (Annotation annotation : annotations) {

                Object[] result = removeDateFragment(annotation.getEntity());
                String entity = (String) result[0];

                annotation.setEntity(entity);
                annotation.setOffset(annotation.getOffset() + (Integer) result[1]);
                annotation.setLength(annotation.getEntity().length());

                if ((Integer) result[1] > 0) {
                    c++;
                }
            }
            LOGGER.info("removed " + c + " partial date annotations in " + stopWatch.getElapsedTimeString());
        }

        // remove annotations that were found to be incorrectly tagged in the training data
        if (removeIncorrectlyTaggedInTraining) {
            stopWatch.start();
            for (Annotation removeAnnotation : removeAnnotations) {
                String removeName = removeAnnotation.getEntity().toLowerCase();
                for (Annotation annotation : annotations) {
                    if (removeName.equals(annotation.getEntity().toLowerCase())) {
                        toRemove.add(annotation);
                    }
                }
            }
            LOGGER.info("removed " + removeAnnotations.size() + " incorrectly tagged entities in training data in "
                    + stopWatch.getElapsedTimeString());
        }

        // load the lingpipe POS tagger only if the features that require them are turned on
        LingPipePosTagger lpt = null;

        if (removeWrongEntityBeginnings || removeSentenceStartErrorsPos || removeSingleNonNounEntities) {
            lpt = new LingPipePosTagger();
            lpt.loadModel();
        }

        // rule-based removal of possibly wrong beginnings of entities, for example "In Ireland" => "Ireland"
        if (removeWrongEntityBeginnings) {
            stopWatch.start();
            for (Annotation annotation : annotations) {

                // if annotation starts at sentence AND if first token of entity has POS tag != NP, NN, JJ, and UH,
                // remove it
                String[] entityParts = annotation.getEntity().split(" ");
                if (entityParts.length > 1 && Boolean.valueOf(annotation.getNominalFeatures().get(0))) {
                    TagAnnotations ta = lpt.tag(entityParts[0]).getTagAnnotations();
                    if (ta.size() == 1 && ta.get(0).getTag().indexOf("NP") == -1
                            && ta.get(0).getTag().indexOf("NN") == -1 && ta.get(0).getTag().indexOf("JJ") == -1
                            && ta.get(0).getTag().indexOf("UH") == -1) {

                        StringBuilder shortEntity = new StringBuilder();
                        for (int i = 1; i < entityParts.length; i++) {
                            shortEntity.append(entityParts[i]).append(" ");
                        }

                        annotation.setEntity(shortEntity.toString().trim());
                        annotation.setOffset(annotation.getOffset() + entityParts[0].length() + 1);
                        annotation.setLength(annotation.getEntity().length());
                        LOGGER.debug("removing beginning: " + entityParts[0] + " => " + annotation.getEntity());
                    }
                }

            }

            LOGGER.info("removed wrong entity beginnings in " + stopWatch.getElapsedTimeString());
        }

        // remove annotations which are at the beginning of a sentence, are some kind of noun but because of the
        // following POS tag probably not an entity
        int c = 0;
        if (removeSentenceStartErrorsPos) {
            stopWatch.start();

            for (Annotation annotation : annotations) {

                // if the annotation is at the start of a sentence
                if (Boolean.valueOf(annotation.getNominalFeatures().get(0))
                        && annotation.getEntity().indexOf(" ") == -1) {

                    TagAnnotations ta = lpt.tag(annotation.getEntity()).getTagAnnotations();
                    if (ta.size() >= 1 && ta.get(0).getTag().indexOf("NP") == -1
                            && ta.get(0).getTag().indexOf("NN") == -1 && ta.get(0).getTag().indexOf("JJ") == -1
                            && ta.get(0).getTag().indexOf("UH") == -1) {
                        continue;
                    }

                    String[] rightContextParts = annotation.getRightContext().split(" ");

                    if (rightContextParts.length == 0) {
                        continue;
                    }

                    ta = lpt.tag(rightContextParts[0]).getTagAnnotations();

                    Set<String> allowedPosTags = new HashSet<String>();
                    allowedPosTags.add("CD");
                    allowedPosTags.add("VB");
                    allowedPosTags.add("VBZ");
                    allowedPosTags.add("VBD");
                    allowedPosTags.add("VBN");
                    allowedPosTags.add("MD");
                    allowedPosTags.add("RB");
                    allowedPosTags.add("NN");
                    allowedPosTags.add("NNS");
                    allowedPosTags.add("NP");
                    allowedPosTags.add("HV");
                    allowedPosTags.add("HVD");
                    allowedPosTags.add("HVZ");
                    allowedPosTags.add("BED");
                    allowedPosTags.add("BER");
                    allowedPosTags.add("BEZ");
                    allowedPosTags.add("BEDZ");
                    allowedPosTags.add(",");
                    allowedPosTags.add("(");
                    allowedPosTags.add("-");
                    allowedPosTags.add("--");
                    allowedPosTags.add(".");
                    allowedPosTags.add("CC");
                    allowedPosTags.add("'");
                    allowedPosTags.add("AP");

                    if (ta.size() > 0 && !allowedPosTags.contains(ta.get(0).getTag())) {
                        c++;
                        toRemove.add(annotation);
                        LOGGER.debug("remove noun at beginning of sentence: " + annotation.getEntity() + "|"
                                + rightContextParts[0] + "|" + ta.get(0).getTag());
                    }

                }
            }

            LOGGER.info("removed " + c + " nouns at beginning of sentence in " + stopWatch.getElapsedTimeString());
        }

        // similar to removeSentenceStartErrorsPos but we use a learned case dictionary to remove possibly incorrectly
        // tagged sentence starts. For example ". This" is removed since "this" is usually spelled using lowercase
        // characters only. This is done NOT only for words at sentence start but all single token words.
        if (removeSentenceStartErrorsCaseDictionary) {
            stopWatch.start();

            for (Annotation annotation : annotations) {

                if (/*
                     * // if the annotation is at the start of a sentence
                     * Boolean.valueOf(annotation.getNominalFeatures().get(0))
                     * &&
                     */annotation.getEntity().indexOf(" ") == -1) {

                    double upperCaseToLowerCaseRatio = 2;

                    CategoryEntries ces = caseDictionary.get(tokenTermMap.get(annotation.getEntity().toLowerCase()));
                    if (ces != null && ces.size() > 0) {
                        double allUpperCase = 0.0;
                        double upperCase = 0.0;
                        double lowerCase = 0.0;

                        if (ces.getCategoryEntry("A") != null) {
                            allUpperCase = ces.getCategoryEntry("A").getRelevance();
                        }

                        if (ces.getCategoryEntry("Aa") != null) {
                            upperCase = ces.getCategoryEntry("Aa").getRelevance();
                        }

                        if (ces.getCategoryEntry("a") != null) {
                            lowerCase = ces.getCategoryEntry("a").getRelevance();
                        }

                        if (lowerCase > 0) {
                            upperCaseToLowerCaseRatio = upperCase / lowerCase;
                        }
                        if (allUpperCase > upperCase && allUpperCase > lowerCase) {
                            upperCaseToLowerCaseRatio = 2;
                        }
                    }

                    if (upperCaseToLowerCaseRatio <= 1) {
                        c++;
                        toRemove.add(annotation);
                        LOGGER.debug("remove word using the case signature: " + annotation.getEntity() + " (ratio:"
                                + upperCaseToLowerCaseRatio + ") | " + annotation.getRightContext());
                    }

                }
            }

            LOGGER.info("removed " + c + " words at beginning of sentence in " + stopWatch.getElapsedTimeString());
        }

        // remove entities which contain only one word which is not a noun (requires POS tagger)
        if (removeSingleNonNounEntities) {
            stopWatch.start();

            c = 0;
            for (Annotation annotation : annotations) {

                TagAnnotations ta = lpt.tag(annotation.getEntity()).getTagAnnotations();
                if (ta.size() == 1 && ta.get(0).getTag().indexOf("NP") == -1 && ta.get(0).getTag().indexOf("NN") == -1
                        && ta.get(0).getTag().indexOf("JJ") == -1 && ta.get(0).getTag().indexOf("UH") == -1) {
                    toRemove.add(annotation);
                    c++;
                }

            }

            LOGGER.info("removed " + c + " non-noun entities in " + stopWatch.getElapsedTimeString());
        }

        LOGGER.info("remove " + toRemove.size() + " entities");
        annotations.removeAll(toRemove);

        // switch using pattern information
        int changed = 0;
        if (switchTagAnnotationsUsingPatterns) {
            stopWatch.start();

            for (Annotation annotation : annotations) {

                String tagNameBefore = annotation.getMostLikelyTagName();

                applyContextAnalysis(annotation);

                if (!annotation.getMostLikelyTagName().equalsIgnoreCase(tagNameBefore)) {
                    LOGGER.debug("changed " + annotation.getEntity() + " from " + tagNameBefore + " to "
                            + annotation.getMostLikelyTagName() + ", left context: " + annotation.getLeftContext()
                            + "____" + annotation.getRightContext());
                    changed++;
                }

            }
            LOGGER.info("changed " + MathHelper.round(100 * changed / (annotations.size() + 0.000000000001), 2)
                    + "% of the entities using patterns in " + stopWatch.getElapsedTimeString());

        }

        // switch annotations that are in the dictionary
        changed = 0;
        if (switchTagAnnotationsUsingDictionary) {
            stopWatch.start();

            for (Annotation annotation : annotations) {

                // CategoryEntries ces = entityDictionary.get(entityTermMap.get(annotation.getEntity()));
                CategoryEntries ces = entityDictionary.get(new Term(annotation.getEntity()));
                if (ces != null && ces.size() > 0) {
                    annotation.assignCategoryEntries(ces);
                    changed++;
                }

            }
            LOGGER.info("changed with entity dictionary "
                    + MathHelper.round(100 * changed / (annotations.size() + 0.000000000001), 2)
                    + "% of the entities (total entities: " + annotations.size() + ") in "
                    + stopWatch.getElapsedTimeString());
        }

        Annotations toAdd = new Annotations();

        stopWatch.start();
        LinkedHashMap<Object, Integer> sortedMap = leftContextMap.getSortedMapDescending();

        for (Annotation annotation : annotations) {

            // remove all annotations with "DOCSTART- " in them because that is for format purposes
            if (annotation.getEntity().toLowerCase().indexOf("docstart") > -1) {
                toRemove.add(annotation);
                continue;
            }

            // if all uppercase, try to find known annotations
            // if (StringHelper.isCompletelyUppercase(annotation.getEntity().substring(10,
            // Math.min(12, annotation.getEntity().length())))) {

            if (unwrapEntities) {
                Annotations wrappedAnnotations = annotation.unwrapAnnotations(annotations, entityDictionary);

                if (!wrappedAnnotations.isEmpty()) {
                    for (Annotation annotation2 : wrappedAnnotations) {
                        if (!annotation2.getMostLikelyTagName().equalsIgnoreCase("###NO_ENTITY###")) {
                            toAdd.add(annotation2);
                            // LOGGER.debug("add " + annotation2.getEntity());
                        }
                    }
                    String debugString = "tried to unwrap again " + annotation.getEntity();
                    for (Annotation wrappedAnnotation : wrappedAnnotations) {
                        debugString += " | " + wrappedAnnotation.getEntity();
                    }
                    debugString += "\n";
                    LOGGER.debug(debugString);
                }
            }

            // unwrap annotations containing context patterns, e.g. "President Obama" => "President" is known left
            // context for people
            // XXX move this up?
            if (unwrapEntitiesWithContext) {
                for (Entry<Object, Integer> leftContextEntry : sortedMap.entrySet()) {

                    String leftContext = leftContextEntry.getKey().toString();

                    // 0 means the context appears more often inside an entity than outside so we should not delete it
                    if (leftContextEntry.getValue() == 0) {
                        // the map is sorted by number of occurrences so we can break as soon as the threshold is
                        // reached
                        break;
                    }

                    if (!StringHelper.startsUppercase(leftContext)) {
                        continue;
                    }

                    String entity = annotation.getEntity();

                    int index1 = entity.indexOf(leftContext + " ");
                    int index2 = entity.indexOf(" " + leftContext + " ");
                    int length = -1;
                    int index = -1;
                    if (index1 == 0) {
                        length = leftContext.length() + 1;
                        index = index1;
                    } else if (index2 > -1) {
                        length = leftContext.length() + 2;
                        index = index2;
                    }
                    if (index1 == 0 || index2 > -1) {

                        // get the annotation after the index
                        Annotation wrappedAnnotation = new Annotation(annotation.getOffset() + index + length,
                                annotation.getEntity().substring(index + length), annotation.getMostLikelyTagName(),
                                annotations);
                        toAdd.add(wrappedAnnotation);

                        // search for a known instance in the prefix
                        // go through the entity dictionary
                        for (Map.Entry<Term, CategoryEntries> termEntry : entityDictionary.entrySet()) {
                            String word = termEntry.getKey().getText();

                            int indexPrefix = annotation.getEntity().substring(0, index + length).indexOf(word + " ");
                            if (indexPrefix > -1 && word.length() > 2) {
                                Annotation wrappedAnnotation2 = new Annotation(annotation.getOffset() + indexPrefix,
                                        word,
                                        termEntry.getValue().getMostLikelyCategoryEntry().getCategory().getName(),
                                        annotations);
                                toAdd.add(wrappedAnnotation2);
                                LOGGER.debug("add from prefix " + wrappedAnnotation2.getEntity());
                                break;
                            }

                        }

                        toRemove.add(annotation);
                        LOGGER.debug("add " + wrappedAnnotation.getEntity() + ", delete " + annotation.getEntity()
                                + " (left context:" + leftContext + ", " + leftContextEntry.getValue() + ")");

                        break;
                    }

                }
            }
        }
        LOGGER.info("unwrapped entities in " + stopWatch.getElapsedTimeString());

        LOGGER.info("add " + toAdd.size() + " entities");
        annotations.addAll(toAdd);

        LOGGER.info("remove " + toRemove.size() + " entities");
        annotations.removeAll(toRemove);
    }

    public Annotations getAnnotationsEnglish(String inputText) {

        Annotations annotations = new Annotations();

        // use the the string tagger to tag entities in English mode
        Annotations entityCandidates = StringTagger.getTaggedEntities(inputText);

        // classify annotations with the UniversalClassifier
        annotations.addAll(classifyCandidatesEnglish(entityCandidates));

        postProcessAnnotations(annotations);

        return annotations;
    }

    public Annotations getAnnotationsLanguageIndependent(String inputText) {

        removeDates = false;
        removeDateEntries = false;
        removeIncorrectlyTaggedInTraining = false;
        removeWrongEntityBeginnings = false;
        removeSentenceStartErrorsPos = false;
        removeSingleNonNounEntities = false;
        switchTagAnnotationsUsingPatterns = false;
        switchTagAnnotationsUsingDictionary = true;
        unwrapEntities = false;
        unwrapEntitiesWithContext = false;

        Annotations annotations = new Annotations();

        // get the candates, every token is potentially a (part of) an entity
        Annotations entityCandidates = StringTagger.getTaggedEntities(inputText, Tokenizer.SPLIT_REGEXP);

        // classify annotations with the UniversalClassifier
        annotations.addAll(classifyCandidatesLanguageIndependent(entityCandidates));

        // filter annotations
        postProcessAnnotations(annotations);

        // combine annotations that are right next to each other having the same tag
        Annotations combinedAnnotations = new Annotations();
        annotations.sort();
        Annotation lastAnnotation = new Annotation(-2, "", "");
        Annotation lastCombinedAnnotation = null;

        for (Annotation annotation : annotations) {
            if (!annotation.getMostLikelyTagName().equalsIgnoreCase("o")
                    && annotation.getMostLikelyTagName().equalsIgnoreCase(lastAnnotation.getMostLikelyTagName())
                    && annotation.getOffset() == lastAnnotation.getEndIndex() + 1) {

                if (lastCombinedAnnotation == null) {
                    lastCombinedAnnotation = lastAnnotation;
                }

                Annotation combinedAnnotation = new Annotation(lastCombinedAnnotation.getOffset(),
                        lastCombinedAnnotation.getEntity() + " " + annotation.getEntity(),
                        annotation.getMostLikelyTagName(), combinedAnnotations);
                combinedAnnotations.add(combinedAnnotation);
                lastCombinedAnnotation = combinedAnnotation;
                combinedAnnotations.remove(lastCombinedAnnotation);
            } else {
                combinedAnnotations.add(annotation);
                lastCombinedAnnotation = null;
            }

            lastAnnotation = annotation;
        }

        // remove all "O"
        Annotations cleanAnnotations = new Annotations();
        for (Annotation annotation : combinedAnnotations) {
            if (!annotation.getMostLikelyTagName().equalsIgnoreCase("o") && annotation.getLength() > 1) {
                cleanAnnotations.add(annotation);
            }
        }

        return cleanAnnotations;
    }

    private void applyContextAnalysis(Annotation annotation) {

        // get the left and right context patterns and merge them into one context pattern list
        String[] leftContexts = annotation.getLeftContexts();
        String[] rightContexts = annotation.getRightContexts();

        List<String> contexts = new ArrayList<String>();
        for (String pattern : leftContexts) {
            contexts.add(pattern);
        }
        for (String pattern : rightContexts) {
            contexts.add(pattern);
        }

        // the probability map holds the probability for the pattern(s) and a certain entity type
        Map<String, Double> probabilityMap = new HashMap<String, Double>();

        // initialize all entity types with one
        for (Object string : patternProbabilityMatrix.getMatrix().keySet()) {
            probabilityMap.put((String) string, 0.0);
        }

        // number of patterns found
        int foundPatterns = 0;

        // check all context patterns left and right
        for (String contextPattern : contexts) {

            contextPattern = contextPattern.toLowerCase();

            // skip empty patterns
            if (contextPattern.length() == 0) {
                continue;
            }

            // count the number of matching patterns per entity type
            CountMap matchingPatternMap = new CountMap();

            int sumOfMatchingPatterns = 0;
            for (Object string : patternProbabilityMatrix.getMatrix().keySet()) {

                Integer matches = (Integer) patternProbabilityMatrix.get(string, contextPattern);
                if (matches == null) {
                    matchingPatternMap.put(string, 0);
                } else {
                    matchingPatternMap.increment(string, matches);
                    sumOfMatchingPatterns += matches;
                }

            }

            if (sumOfMatchingPatterns > 0) {
                foundPatterns++;
            } else {
                continue;
            }

            for (Object string : patternProbabilityMatrix.getMatrix().keySet()) {
                Double double1 = probabilityMap.get(string);

                double1 += matchingPatternMap.get(string) / (double) sumOfMatchingPatterns;

                probabilityMap.put((String) string, double1);
            }
        }

        CategoryEntries ce = new CategoryEntries();

        for (Object string : patternProbabilityMatrix.getMatrix().keySet()) {
            ce.add(new CategoryEntry(ce, new Category((String) string), probabilityMap.get(string)));
        }

        TextInstance ti = contextClassifier.classify(annotation.getLeftContext() + "__" + annotation.getRightContext());

        CategoryEntries ceMerge = new CategoryEntries();
        ceMerge.addAllRelative(ce);
        ceMerge.addAllRelative(annotation.getAssignedCategoryEntries());
        ceMerge.addAllRelative(ti.getAssignedCategoryEntries());
        annotation.assignCategoryEntries(ceMerge);
    }

    @Override
    public Annotations getAnnotations(String inputText, String modelPath) {
        loadModel(modelPath);
        return getAnnotations(inputText);
    }

    /**
     * <p>Check whether the given text contains a date fragment. For example "June John Hiatt" would return true.</p>
     * 
     * @param text The text to check for date fragments.
     * @return <tt>True</tt>, if the text contains a date fragment, <tt>false</tt> otherwise.
     */
    private boolean containsDateFragment(String text) {
        text = text.toLowerCase();
        String[] regExps = RegExp.getDateFragmentRegExp();

        for (String regExp : regExps) {
            if (text.replaceAll(regExp.toLowerCase(), "").trim().isEmpty()) {
                return true;
            }

        }

        return false;
    }

    /**
     * <p>Remove date fragments from the given text.</p>
     * 
     * @param text The text to be cleased of date fragments.
     * @return An object array containing the new cleased text on position 0 and the offset which was caused by the
     *         removal on position 1.
     */
    private Object[] removeDateFragment(String text) {
        String[] regExps = RegExp.getDateFragmentRegExp();

        Object[] result = new Object[2];
        int offsetChange = 0;

        for (String regExp : regExps) {
            int textLength = text.length();

            // for example "Apr John Hiatt"
            if (StringHelper.countOccurences(text, "^" + regExp + " ", false) > 0) {
                text = text.replaceAll("^" + regExp + " ", "").trim();
                offsetChange += textLength - text.length();
            }
            if (StringHelper.countOccurences(text, " " + regExp + "$", false) > 0) {
                text = text.replaceAll(" " + regExp + "$", "").trim();
            }

            // for example "Apr. John Hiatt"
            if (StringHelper.countOccurences(text, "^" + regExp + "\\. ", false) > 0) {
                text = text.replaceAll("^" + regExp + "\\. ", "").trim();
                offsetChange += textLength - text.length();
            }
            if (StringHelper.countOccurences(text, " " + regExp + "\\.$", false) > 0) {
                text = text.replaceAll(" " + regExp + "\\.$", "").trim();
            }
        }

        result[0] = text;
        result[1] = offsetChange;

        return result;
    }

    /**
     * <p>Analyze the context around the annotations. The context classifier will be trained and left context patterns will
     * be stored.</p>
     * 
     * @param trainingFilePath The path to the training data.
     * @param trainingAnnotations The training annotations.
     */
    private void analyzeContexts(String trainingFilePath, Annotations trainingAnnotations) {

        Map<String, CountMap> contextMap = new TreeMap<String, CountMap>();
        CountMap leftContextMapCountMap = new CountMap();
        leftContextMap = new CountMap();
        // rightContextMap = new TreeMap<String, CountMap>();
        CountMap tagCounts = new CountMap();

        // get all training annotations including their features
        Annotations annotations = FileFormatParser.getAnnotationsFromColumn(trainingFilePath);

        Instances<UniversalInstance> trainingInstances = new Instances<UniversalInstance>();

        // iterate over all annotations and analyze their left and right contexts for patterns
        for (Annotation annotation : annotations) {

            String tag = annotation.getInstanceCategoryName();

            // the left patterns containing 1-3 words
            String[] leftContexts = annotation.getLeftContexts();

            // the right patterns containing 1-3 words
            String[] rightContexts = annotation.getRightContexts();

            // initialize tagMap
            // if (rightContextMap.get(tag) == null) {
            // rightContextMap.put(tag, new CountMap());
            // }
            if (contextMap.get(tag) == null) {
                contextMap.put(tag, new CountMap());
            }

            // add the left contexts to the map
            contextMap.get(tag).increment(leftContexts[0]);
            contextMap.get(tag).increment(leftContexts[1]);
            contextMap.get(tag).increment(leftContexts[2]);

            leftContextMapCountMap.increment(leftContexts[0]);
            leftContextMapCountMap.increment(leftContexts[1]);
            leftContextMapCountMap.increment(leftContexts[2]);

            // add the right contexts to the map
            contextMap.get(tag).increment(rightContexts[0]);
            contextMap.get(tag).increment(rightContexts[1]);
            contextMap.get(tag).increment(rightContexts[2]);

            // rightContextMap.get(tag).increment(rightContexts[0]);
            // rightContextMap.get(tag).increment(rightContexts[1]);
            // rightContextMap.get(tag).increment(rightContexts[2]);

            tagCounts.increment(tag);

            UniversalInstance trainingInstance = new UniversalInstance(trainingInstances);
            trainingInstance.setTextFeature(annotation.getLeftContext() + "__" + annotation.getRightContext());
            trainingInstance.setInstanceCategory(tag);
            trainingInstances.add(trainingInstance);
        }

        // fill the leftContextMap with the context and the ratio of inside annotation / outside annotation
        for (Entry<Object, Integer> entry : leftContextMapCountMap.entrySet()) {
            String leftContext = entry.getKey().toString();
            int outside = entry.getValue();
            int inside = 0;

            for (Annotation annotation : annotations) {
                if (annotation.getEntity().startsWith(leftContext + " ") || annotation.getEntity().equals(leftContext)) {
                    inside++;
                }
            }

            double ratio = (double) inside / (double) outside;
            if (ratio >= 1 || outside < 2) {
                leftContextMap.put(leftContext, 0);
            } else {
                leftContextMap.put(leftContext, 1);
            }

        }
        // leftContextMap = leftContextMapCountMap;

        contextClassifier.setTrainingInstances(trainingInstances);
        contextClassifier.train();

        StringBuilder csv = new StringBuilder();
        for (Entry<String, CountMap> entry : contextMap.entrySet()) {

            int tagCount = tagCounts.get(entry.getKey());
            CountMap patterns = contextMap.get(entry.getKey());
            LinkedHashMap<Object, Integer> sortedMap = patterns.getSortedMap();

            csv.append(entry.getKey()).append("###").append(tagCount).append("\n");

            // print the patterns and their count for the current tag
            for (Entry<Object, Integer> patternEntry : sortedMap.entrySet()) {
                if (patternEntry.getValue() > 0) {
                    csv.append(patternEntry.getKey()).append("###").append(patternEntry.getValue()).append("\n");
                }
            }

            csv.append("++++++++++++++++++++++++++++++++++\n\n");
        }

        // tagMap to matrix
        for (Entry<String, CountMap> patternEntry : contextMap.entrySet()) {

            for (Entry<Object, Integer> tagEntry : patternEntry.getValue().entrySet()) {
                patternProbabilityMatrix.set(patternEntry.getKey(), tagEntry.getKey().toString().toLowerCase(),
                        tagEntry.getValue());
            }

        }

        // FileHelper.writeToFile("data/temp/tagPatternAnalysis.csv", csv);
    }

    public LanguageMode getLanguageMode() {
        return languageMode;
    }

    public void setLanguageMode(LanguageMode languageMode) {
        this.languageMode = languageMode;
    }

    public void setTrainingMode(TrainingMode trainingMode) {
        this.trainingMode = trainingMode;
        if (trainingMode == TrainingMode.Sparse) {
            removeDates = true;
            removeDateEntries = true;
            removeIncorrectlyTaggedInTraining = false;
            removeWrongEntityBeginnings = false;
            removeSentenceStartErrorsPos = false;
            removeSentenceStartErrorsCaseDictionary = true;
            removeSingleNonNounEntities = false;
            switchTagAnnotationsUsingPatterns = true;
            switchTagAnnotationsUsingDictionary = true;
            unwrapEntities = true;
            unwrapEntitiesWithContext = true;
            retraining = false;
        }
    }

    public TrainingMode getTrainingMode() {
        return trainingMode;
    }

    public Dictionary getEntityDictionary() {
        return entityDictionary;
    }

    public void setEntityDictionary(Dictionary entityDictionary) {
        this.entityDictionary = entityDictionary;
    }

    // public void addToEntityDictionary(Dictionary entityDictionary) {
    // for (Entry<Term, CategoryEntries> entry : entityDictionary.entrySet()) {
    // this.entityDictionary.updateWord(entry.getKey(), entry.getValue().get(0), 1);
    // }
    // }

    /**
     * Create an h2 database dictionary from a dictionary file with the following format:<br>
     * Entity;Type
     * 
     * @param dictionaryPath The path of the dictionary text file.
     */
    public void makeDictionary(String dictionaryPath) {

        StopWatch stopWatch = new StopWatch();

        final Dictionary dictionary = new Dictionary("entityDictionary");
        dictionary.setCaseSensitive(true);
        dictionary.setIndexPath("data/models/");

        final int totalLines = FileHelper.getNumberOfLines(dictionaryPath);

        LineAction lineAction = new LineAction() {

            @Override
            public void performAction(String line, int lineNumber) {

                // if (lineNumber > 20000) {
                // return;
                // }

                String[] parts = line.split(";");

                if (parts.length != 2) {
                    LOGGER.warn("line " + lineNumber + " is not well formatted");
                    return;
                }

                String entity = parts[0];
                String type = parts[1];

                if (entity.length() > DictionaryDbIndexH2.MAX_WORD_LENGTH || type.length() > 25) {
                    LOGGER.warn("input too long (max. " + DictionaryDbIndexH2.MAX_WORD_LENGTH
                            + " characters per field): " + entity + "," + type);
                    return;
                }

                dictionary.updateWord(new Term(entity), type, 1);

                if (lineNumber % 1000 == 0) {
                    LOGGER.info("progress: " + MathHelper.round(100 * lineNumber / (double) totalLines, 2) + "%");
                }
            }

        };

        FileHelper.performActionOnEveryLine(dictionaryPath, lineAction);

        LOGGER.info("serialize dictionary now...");

        FileHelper.serialize(dictionary, "dict.ser.gz");
        /*
         * dictionary.serialize("dict.ser", true, true);
         * dictionary.useIndex();
         * CategoryEntries categoryEntries2 = dictionary.get(new Term("Cape Town"));
         * System.out.println(categoryEntries2);
         */

        LOGGER.info("dictionary creation took " + stopWatch.getTotalElapsedTimeString());
    }

    public void setTagUrls(boolean tagUrls) {
		this.tagUrls = tagUrls;
	}

	public boolean isTagUrls() {
		return tagUrls;
	}

	public void setTagDates(boolean tagDates) {
		this.tagDates = tagDates;
	}

	public boolean isTagDates() {
		return tagDates;
	}
	
    /**
     * @param args
     */
    @SuppressWarnings("static-access")
    public static void main(String[] args) {

        LOGGER.setLevel(Level.INFO);
        PalladianNer tagger = new PalladianNer();

        if (args.length > 0) {

            Options options = new Options();
            options.addOption(OptionBuilder.withLongOpt("mode").withDescription("whether to tag or train a model")
                    .create());

            OptionGroup modeOptionGroup = new OptionGroup();
            modeOptionGroup.addOption(OptionBuilder.withArgName("tg").withLongOpt("tag").withDescription("tag a text")
                    .create());
            modeOptionGroup.addOption(OptionBuilder.withArgName("tr").withLongOpt("train")
                    .withDescription("train a model").create());
            modeOptionGroup.addOption(OptionBuilder.withArgName("ev").withLongOpt("evaluate")
                    .withDescription("evaluate a model").create());
            modeOptionGroup.addOption(OptionBuilder.withArgName("dm").withLongOpt("demo")
                    .withDescription("demo mode of the tagger").create());
            modeOptionGroup.setRequired(true);
            options.addOptionGroup(modeOptionGroup);

            options.addOption(OptionBuilder.withLongOpt("trainingFile")
                    .withDescription("the path and name of the training file for the tagger (only if mode = train)")
                    .hasArg().withArgName("text").withType(String.class).create());

            options.addOption(OptionBuilder
                    .withLongOpt("testFile")
                    .withDescription(
                            "the path and name of the test file for evaluating the tagger (only if mode = evaluate)")
                    .hasArg().withArgName("text").withType(String.class).create());

            options.addOption(OptionBuilder.withLongOpt("configFile")
                    .withDescription("the path and name of the config file for the tagger").hasArg()
                    .withArgName("text").withType(String.class).create());

            options.addOption(OptionBuilder.withLongOpt("inputText")
                    .withDescription("the text that should be tagged (only if mode = tag)").hasArg()
                    .withArgName("text").withType(String.class).create());

            options.addOption(OptionBuilder.withLongOpt("outputFile")
                    .withDescription("the path and name of the file where the tagged text should be saved to").hasArg()
                    .withArgName("text").withType(String.class).create());

            HelpFormatter formatter = new HelpFormatter();

            CommandLineParser parser = new PosixParser();
            CommandLine cmd = null;
            try {
                cmd = parser.parse(options, args);

                if (cmd.hasOption("tag")) {

                    String taggedText = tagger.tag(cmd.getOptionValue("inputText"), cmd.getOptionValue("configFile"));

                    if (cmd.hasOption("outputFile")) {
                        FileHelper.writeToFile(cmd.getOptionValue("outputFile"), taggedText);
                    } else {
                        System.out.println("No output file given so tagged text will be printed to the console:");
                        System.out.println(taggedText);
                    }

                } else if (cmd.hasOption("train")) {

                    tagger.train(cmd.getOptionValue("trainingFile"), cmd.getOptionValue("configFile"));

                } else if (cmd.hasOption("evaluate")) {

                    tagger.evaluate(cmd.getOptionValue("trainingFile"), cmd.getOptionValue("configFile"),
                            TaggingFormat.XML);

                }

            } catch (ParseException e) {
                LOGGER.debug("Command line arguments could not be parsed!");
                formatter.printHelp("FeedChecker", options);
            }

        }

        // ################################# HOW TO USE #################################

        // // training the tagger
        // needs to point to a column separated file
        String trainingPath = "data/datasets/ner/conll/training.txt";
        //trainingPath = "data/temp/seedsTest100.txt";
        //trainingPath = "data/datasets/ner/tud/tud2011_train.txt";
        String modelPath = "data/temp/palladianNerTudCs4Annotations";
        modelPath = "data/temp/palladianNerConllAnnotations";

        // set mode (English or language independent)
        tagger.setLanguageMode(LanguageMode.English);

        // set type of training set (complete supervised or sparse semi-supervised)
        tagger.setTrainingMode(TrainingMode.Complete);

        // create a dictionary from a dictionary txt file
        // tagger.makeDictionary("mergedDictComplete.csv");

        // we can add annotations without any context to the tagger to improve internal evidence features
        String trainingSeedFilePath = PalladianNer.class.getResource("/nerSeeds.txt").getFile();
        Annotations trainingAnnotations = FileFormatParser.getSeedAnnotations(trainingSeedFilePath, -1);

        // train the tagger on the training file (with or without additional training annotations)
        tagger.train(trainingPath, trainingAnnotations, modelPath);
        // tagger.train(trainingPath, modelPath);

        // // using a trained tagger
        // load a trained tagger
        tagger.loadModel(modelPath);

        // load an additional entity dictionary
        StopWatch sw2 = new StopWatch();
        // Dictionary dict = FileHelper.deserialize("dict.ser.gz");
        // LOGGER.info(sw2.getTotalElapsedTimeString());
        // tagger.setEntityDictionary(dict);

        // tag a sentence
        String inputText = "Peter J. Johnson lives in New York City in the U.S.A.";
        String taggedText = tagger.tag(inputText);
        System.out.println(taggedText);

        CollectionHelper.print(tagger.getAnnotations(inputText));
        
        System.exit(0);

        // // evaluate a tagger
        String testPath = "data/datasets/ner/conll/test_final.txt";
        testPath = "data/datasets/ner/tud/tud2011_test.txt";
        EvaluationResult evr = tagger.evaluate(testPath, TaggingFormat.COLUMN);
        System.out.println(evr.getMUCResultsReadable());
        System.out.println(evr.getExactMatchResultsReadable());

        // CoNLL
        // without the dictionary
        // precision MUC: 76.19%, recall MUC: 82.25%, F1 MUC: 79.1%
        // precision exact: 64.54%, recall exact: 69.67%, F1 exact: 67.01%

        // with the dbpedia dictionary BUT the types of conll and dbpedia do not match therefore a worse result
        // precision MUC: 57.09%, recall MUC: 62.96%, F1 MUC: 59.88%
        // precision exact: 30.41%, recall exact: 33.54%, F1 exact: 31.9%

        // TUDCS4
        // without the dictionary
        // precision MUC: 52.12%, recall MUC: 53.36%, F1 MUC: 52.73%
        // precision exact: 29.4%, recall exact: 30.11%, F1 exact: 29.75%

        // with additional training annotations (src/main/resources/nerSeeds.txt)
        // precision MUC: 51.92%, recall MUC: 64.46%, F1 MUC: 57.52%
        // precision exact: 31.95%, recall exact: 39.66%, F1 exact: 35.39%

        // learned from automatically generated training data, sparse (100 seeds)
        // precision MUC: 42.56%, recall MUC: 51.76%, F1 MUC: 46.71%
        // precision exact: 16.49%, recall exact: 20.05%, F1 exact: 18.09%

        System.exit(0);

        // EvaluationResult er = tagger.evaluate("data/datasets/ner/conll/test_validation.txt",
        // "data/temp/tudner.model",
        // TaggingFormat.COLUMN);
        // EvaluationResult er = tagger.evaluate("data/datasets/ner/conll/test_final.txt", "", TaggingFormat.COLUMN);
        // EvaluationResult er = tagger.evaluate(testFilePath, "", TaggingFormat.COLUMN);
        // System.out.println(er.getMUCResultsReadable());
        // System.out.println(er.getExactMatchResultsReadable());
        //
        // System.out.println(stopWatch.getElapsedTimeString());
        //
        // HashSet<String> trainingTexts = new HashSet<String>();
        // trainingTexts
        // .add("Australia is a country and a continent at the same time. New Zealand is also a country but not a continent");
        // trainingTexts
        // .add("Many countries, such as Germany and Great Britain, have a strong economy. Other countries such as Iceland and Norway are in the north and have a smaller population");
        // trainingTexts.add("In south Europe, a nice country named Italy is formed like a boot.");
        // trainingTexts.add("In the western part of Europe, the is a country named Spain which is warm.");
        // trainingTexts
        // .add("Bruce Willis is an actor, Jim Carrey is an actor too, but Trinidad is a country name and and actor name as well.");
        // trainingTexts.add("In west Europe, a warm country named Spain has good seafood.");
        // trainingTexts.add("Another way of thinking of it is to drive to another coutry and have some fun.");
        //
        // // possible tags
        // CollectionHelper.print(tagger.getModelTags("data/models/tudner/tudner.model"));
        //
        // // train
        // tagger.train("data/datasets/ner/sample/trainingColumn.tsv", "data/models/tudner/tudner.model");
        //
        // // tag
        // tagger.loadModel("data/models/tudner/tudner.model");
        // tagger.tag("John J. Smith and the Nexus One location iphone 4 mention Seattle in the text John J. Smith lives in Seattle.");
        //
        // tagger.tag(
        // "John J. Smith and the Nexus One location iphone 4 mention Seattle in the text John J. Smith lives in Seattle.",
        // "data/models/tudner/tudner.model");
        //
        // // evaluate
        // tagger.evaluate("data/datasets/ner/sample/testingColumn.tsv", "data/models/tudner/tudner.model",
        // TaggingFormat.COLUMN);

        // /////////////////////////// train and test /////////////////////////////
        // tagger.train("data/datasets/ner/politician/text/training.tsv", "data/models/tudner/tudner.model");
        // EvaluationResult er = tagger.evaluate("data/datasets/ner/politician/text/testing.tsv",
        // "data/models/tudner/tudner.model", TaggingFormat.COLUMN);
        // System.out.println(er.getMUCResultsReadable());
        // System.out.println(er.getExactMatchResultsReadable());

        LOGGER.setLevel(Level.DEBUG);

        // using a column trainig and testing file
        String trainingFilePath = "data/temp/autoGeneratedDataConll/seedsTest1.txt";
        // trainingFilePath = "data/temp/autoGeneratedDataTUD2/seedsTest1.txt";
        // trainingFilePath = "data/temp/autoGeneratedDataTUD4/seedsTest50.txt";
        // trainingFilePath = "data/datasets/ner/conll/training_small.txt";
        // trainingFilePath = "data/temp/seedsTest100.txt";

        String testFilePath = "data/datasets/ner/conll/test_final.txt";
        testFilePath = "data/datasets/ner/tud/tud2011_test.txt";

        String seedFilePath = "data/datasets/ner/conll/training.txt";
        seedFilePath = "data/datasets/ner/tud/manuallyPickedSeeds/seedListC.txt";

        StopWatch stopWatch = new StopWatch();

        // /////////////////////// evaluation purposes //////////////////////////
        StringBuilder evaluationResults = new StringBuilder();
        Annotations ignoreAnnotations = FileFormatParser.getSeedAnnotations(trainingFilePath, -1);
        String datasetFolder = "data/temp/autoGeneratedDataTUD4/";
        datasetFolder = "data/temp/autoGeneratedDataConll/";
        datasetFolder = "data/temp/autoGeneratedDataTUD/";

        // for (int i = 1; i <= 100; i += 10) {
        for (int i = 1; i <= 5; i++) {

            int j = i;
            if (j > 1) {
                j *= 10;
            }
            j = 10;
            trainingFilePath = datasetFolder + "newDataset" + j + ".txt";

            tagger = new PalladianNer();
            tagger.setLanguageMode(LanguageMode.English);
            tagger.setTrainingMode(TrainingMode.Sparse);

            // Annotations annotations = FileFormatParser.getSeedAnnotations(seedFilePath, i);
            // tagger.train(trainingFilePath, annotations, "data/temp/tudner2.model");
            tagger.train(trainingFilePath, "data/temp/tudner");
            // tagger.train(annotations, "data/temp/tudner2.model");
            tagger.loadModel("data/temp/tudner");

            EvaluationResult er = tagger.evaluate(testFilePath, "", TaggingFormat.COLUMN, ignoreAnnotations);

            evaluationResults.append(er.getPrecision(EvaluationResult.EXACT_MATCH)).append(";");
            evaluationResults.append(er.getRecall(EvaluationResult.EXACT_MATCH)).append(";");
            evaluationResults.append(er.getF1(EvaluationResult.EXACT_MATCH)).append(";");
            evaluationResults.append(er.getPrecision(EvaluationResult.MUC)).append(";");
            evaluationResults.append(er.getRecall(EvaluationResult.MUC)).append(";");
            evaluationResults.append(er.getF1(EvaluationResult.MUC)).append(";");

            evaluationResults.append("\n");
            FileHelper.writeToFile("results.txt", evaluationResults);
        }
        System.exit(0);
        // 2-8, 4-5, 040: 0.3912314995811226;
        // 2-8, 4-5, 040, seedsText2: 0.40139470013947
        // 2-8, 4-5, 120: 0.39039374476403244;
        // 2-8, 4-7, 040: 0.3937447640323932;
        // 2-8, 4-5, 040, leftContext 31 count: 0.41089385474860335
        // 2-8, 4-5, 040: seedsText50, 0.44447560291643295
        // 2-8, 4-5, 040: no seed text, 0.33059735522115824
        // //////////////////////////////////////////////////////////////////////

        // tagger.setTrainingMode(TrainingMode.Complete);
        // tagger.train("data/datasets/ner/conll/training.txt", "data/temp/tudner.model");
        // tagger.train("data/temp/nerEvaluation/www_eval_2_cleansed/allColumn.txt", "data/temp/tudner.model");

        tagger.setLanguageMode(LanguageMode.English);
        tagger.setTrainingMode(TrainingMode.Complete);

        Annotations annotations = FileFormatParser.getSeedAnnotations(
                "data/datasets/ner/tud/manuallyPickedSeeds/seedListC.txt", 50);
        // tagger.train(annotations, "data/temp/tudner");
        tagger.train(trainingFilePath, "data/temp/tudner");
        // tagger.train(trainingFilePath, annotations, "data/temp/tudner2.model");
        // System.exit(0);
        // TUDNER.remove = true;
        tagger.loadModel("data/temp/tudner");
        // System.exit(0);

        // tagger = TUDNER.load("data/temp/tudner.model");

        // EvaluationResult er = tagger.evaluate("data/datasets/ner/conll/test_validation.txt",
        // "data/temp/tudner.model",
        // TaggingFormat.COLUMN);
        // EvaluationResult er = tagger.evaluate("data/datasets/ner/conll/test_final.txt", "", TaggingFormat.COLUMN);
        EvaluationResult er = tagger.evaluate(testFilePath, "", TaggingFormat.COLUMN);
        System.out.println(er.getMUCResultsReadable());
        System.out.println(er.getExactMatchResultsReadable());

        System.out.println(stopWatch.getElapsedTimeString());

        // Dataset trainingDataset = new Dataset();
        // trainingDataset.setPath("data/datasets/ner/www_test2/index_split1.txt");
        // tagger.train(trainingDataset, "data/models/tudner/tudner.model");
        //
        // Dataset testingDataset = new Dataset();
        // testingDataset.setPath("data/datasets/ner/www_test2/index_split2.txt");
        // EvaluationResult er = tagger.evaluate(testingDataset, "data/models/tudner/tudner.model");
        // System.out.println(er.getMUCResultsReadable());
        // System.out.println(er.getExactMatchResultsReadable());
    }
	
}