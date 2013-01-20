package ws.palladian.extraction.keyphrase.extractors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import ws.palladian.classification.CategoryEntries;
import ws.palladian.classification.Instance;
import ws.palladian.classification.dt.BaggedDecisionTreeClassifier;
import ws.palladian.classification.dt.BaggedDecisionTreeModel;
import ws.palladian.extraction.feature.DuplicateTokenConsolidator;
import ws.palladian.extraction.feature.DuplicateTokenRemover;
import ws.palladian.extraction.feature.HtmlCleaner;
import ws.palladian.extraction.feature.IdfAnnotator;
import ws.palladian.extraction.feature.LengthTokenRemover;
import ws.palladian.extraction.feature.NGramCreator;
import ws.palladian.extraction.feature.RegExTokenRemover;
import ws.palladian.extraction.feature.StemmerAnnotator;
import ws.palladian.extraction.feature.StemmerAnnotator.Mode;
import ws.palladian.extraction.feature.StopTokenRemover;
import ws.palladian.extraction.feature.TermCorpus;
import ws.palladian.extraction.feature.TextDocumentPipelineProcessor;
import ws.palladian.extraction.feature.TfIdfAnnotator;
import ws.palladian.extraction.feature.TokenMetricsCalculator;
import ws.palladian.extraction.keyphrase.Keyphrase;
import ws.palladian.extraction.keyphrase.KeyphraseExtractor;
import ws.palladian.extraction.keyphrase.features.AdditionalFeatureExtractor;
import ws.palladian.extraction.keyphrase.temp.CooccurrenceMatrix;
import ws.palladian.extraction.token.BaseTokenizer;
import ws.palladian.extraction.token.RegExTokenizer;
import ws.palladian.helper.collection.CollectionHelper;
import ws.palladian.helper.constants.Language;
import ws.palladian.processing.DocumentUnprocessableException;
import ws.palladian.processing.PerformanceCheckProcessingPipeline;
import ws.palladian.processing.PipelineDocument;
import ws.palladian.processing.ProcessingPipeline;
import ws.palladian.processing.TextDocument;
import ws.palladian.processing.features.Feature;
import ws.palladian.processing.features.FeatureVector;
import ws.palladian.processing.features.NominalFeature;
import ws.palladian.processing.features.NumericFeature;
import ws.palladian.processing.features.PositionAnnotation;

public final class MachineLearningBasedExtractor extends KeyphraseExtractor {

    static final String IS_KEYWORD = "isKeyword";

    // private final int TRAIN_DOC_LIMIT = Integer.MAX_VALUE;
    // private final int TRAIN_DOC_LIMIT = 50;
    private final int TRAIN_DOC_LIMIT = 100;
    // private final int TRAIN_DOC_LIMIT = 150;
    private final ProcessingPipeline corpusGenerationPipeline;
    private final ProcessingPipeline candidateGenerationPipeline;
    private final TermCorpus termCorpus;
    private final TermCorpus keyphraseCorpus;
    private final CooccurrenceMatrix<String> cooccurrenceMatrix;
    private StemmerAnnotator stemmer;
    private int trainCount;
    private final Map<PipelineDocument<String>, Set<String>> trainDocuments;
    private BaggedDecisionTreeClassifier classifier;
    private BaggedDecisionTreeModel model;

    public MachineLearningBasedExtractor() {
        termCorpus = new TermCorpus();
        keyphraseCorpus = new TermCorpus();
        cooccurrenceMatrix = new CooccurrenceMatrix<String>();
        trainCount = 0;
        trainDocuments = new HashMap<PipelineDocument<String>, Set<String>>();
        classifier = createClassifier();

        corpusGenerationPipeline = new PerformanceCheckProcessingPipeline();
        corpusGenerationPipeline.add(new HtmlCleaner());
        corpusGenerationPipeline.add(new RegExTokenizer());
        corpusGenerationPipeline.add(new StopTokenRemover(Language.ENGLISH));
        corpusGenerationPipeline.add(new LengthTokenRemover(4));
        corpusGenerationPipeline.add(new RegExTokenRemover("[^A-Za-z0-9-]+"));
        stemmer = new StemmerAnnotator(Language.ENGLISH, Mode.MODIFY);
        corpusGenerationPipeline.add(stemmer);
        corpusGenerationPipeline.add(new DuplicateTokenRemover());

        // extractionPipeline has the same steps as trainingPipeline,
        // plus idf and tf-idf annotation
        candidateGenerationPipeline = new ProcessingPipeline();
        candidateGenerationPipeline.add(new HtmlCleaner());
        candidateGenerationPipeline.add(new RegExTokenizer());
        candidateGenerationPipeline.add(new StopTokenRemover(Language.ENGLISH));
        candidateGenerationPipeline.add(new LengthTokenRemover(4));
        candidateGenerationPipeline.add(new RegExTokenRemover("[^A-Za-z0-9-]+"));
        candidateGenerationPipeline.add(stemmer);
        candidateGenerationPipeline.add(new NGramCreator(3, StemmerAnnotator.UNSTEM));
        candidateGenerationPipeline.add(new TokenMetricsCalculator());
        candidateGenerationPipeline.add(new DuplicateTokenConsolidator());
        candidateGenerationPipeline.add(new IdfAnnotator(termCorpus));
        candidateGenerationPipeline.add(new TfIdfAnnotator());
//        candidateGenerationPipeline.add(new PhrasenessAnnotator());
        candidateGenerationPipeline.add(new AdditionalFeatureExtractor());
        candidateGenerationPipeline.add(new TextDocumentPipelineProcessor() {

            @Override
            public void processDocument(TextDocument document) throws DocumentUnprocessableException {
                List<PositionAnnotation> tokenAnnotations = BaseTokenizer.getTokenAnnotations(document);
                for (PositionAnnotation annotation : tokenAnnotations) {
                    double prior = (double)(keyphraseCorpus.getCount(annotation.getValue()) + 1)
                            / keyphraseCorpus.getNumDocs();
                    annotation.getFeatureVector().add(new NumericFeature("prior", prior));
                }
            }
        });

    }

    private BaggedDecisionTreeClassifier createClassifier() {
        BaggedDecisionTreeClassifier baggedClassifier = new BaggedDecisionTreeClassifier();
        return baggedClassifier;
    }

    @Override
    public boolean needsTraining() {
        return true;
    }

    @Override
    public void startTraining() {
        System.out.println("Building corpus ...");
        super.startTraining();
    }

    @Override
    public void train(String inputText, Set<String> keyphrases) {
        TextDocument document = new TextDocument(inputText);
        try {
            corpusGenerationPipeline.process(document);
        } catch (DocumentUnprocessableException e) {
            throw new IllegalStateException(e);
        }
        List<PositionAnnotation> annotations = document.getFeatureVector().getAll(PositionAnnotation.class, RegExTokenizer.PROVIDED_FEATURE);
        Set<String> terms = new HashSet<String>();
        for (PositionAnnotation annotation : annotations) {
            terms.add(annotation.getValue());
        }
        termCorpus.addTermsFromDocument(terms);
        keyphraseCorpus.addTermsFromDocument(stem(keyphrases));
        cooccurrenceMatrix.addAll(stem(keyphrases));
        if (trainCount <= TRAIN_DOC_LIMIT) {
            trainDocuments.put(document, keyphrases);
        }
        trainCount++;
    }

    @Override
    public void endTraining() {
        System.out.println("finished building corpus, # train docs: " + trainDocuments.size());
        List<PositionAnnotation> annotations = CollectionHelper.newArrayList();
        Iterator<Entry<PipelineDocument<String>, Set<String>>> trainDocIterator = trainDocuments.entrySet().iterator();
        int totalKeyphrases = 0;
        int totallyMarked = 0;
        while (trainDocIterator.hasNext()) {
            Entry<PipelineDocument<String>, Set<String>> currentEntry = trainDocIterator.next();
            PipelineDocument<String> currentDoc = currentEntry.getKey();
            Set<String> keywords = currentEntry.getValue();
            try {
                candidateGenerationPipeline.process(currentDoc);
            } catch (DocumentUnprocessableException e) {
                throw new IllegalStateException(e);
            }
            List<PositionAnnotation> annotationFeature = currentDoc.getFeatureVector().getAll(
                    PositionAnnotation.class, BaseTokenizer.PROVIDED_FEATURE);
            totalKeyphrases += keywords.size();
            totallyMarked += markCandidates(annotationFeature, keywords);
            annotations.addAll(annotationFeature);
            trainDocIterator.remove();
            System.out.println(trainDocuments.size());
        }
        System.out.println("# annotations: " + annotations.size());
        System.out.println("% sample coverage: " + (double)totallyMarked / totalKeyphrases);
        int posSamples = 0;
        int negSamples = 0;
        List<Instance> instances = new ArrayList<Instance>();
        for (PositionAnnotation annotation : annotations) {
            FeatureVector featureVector = annotation.getFeatureVector();
            String targetClass = featureVector.getFeature(NominalFeature.class, IS_KEYWORD).getValue();
            FeatureVector cleanedFv = cleanFeatureVector(featureVector);
            if ("true".equals(targetClass)) {
                posSamples++;
            } else {
                negSamples++;
            }
            Instance instance = new Instance(targetClass,cleanedFv);
            instances.add(instance);
        }
        System.out.println("# negative samples: " + negSamples);
        System.out.println("# positive samples: " + posSamples);
        System.out.println("% positive sample rate: " + (double)posSamples / (negSamples + posSamples));
        System.out.println("building classifier ...");
        this.model = classifier.train(instances);
        System.out.println(model.toString());
        System.out.println("... finished building classifier.");
    }

    /**
     * Remove those {@link Feature}s which are not to be processed by the {@link Predictor}.
     * 
     * @param featureVector
     * @return
     */
    private FeatureVector cleanFeatureVector(FeatureVector featureVector) {
        FeatureVector result = new FeatureVector(featureVector);
        result.removeAll(IS_KEYWORD);
        result.removeAll(StemmerAnnotator.UNSTEM);
        result.removeAll(BaseTokenizer.PROVIDED_FEATURE); // XXX was duplicate token annotation
        result.removeAll(AdditionalFeatureExtractor.CASE_SIGNATURE);
        return result;
    }

    /**
     * <p>
     * Takes a list of candidates in form of {@link PositionAnnotation}s and a list of "real" keyphrases and tries to match
     * those keyphrases in the supplied annotations. Use fuzzy/multiple variant matching for high recall (i.e. match as
     * many annotations as possible).
     * </p>
     * 
     * @param annotationFeature
     * @param keywords
     * @return
     */
    private int markCandidates(List<PositionAnnotation> annotations, Set<String> keywords) {
        Set<String> modifiedKeywords = new HashSet<String>();
        int marked = 0;
        // try to match multiple different variants
        for (String keyword : keywords) {
            modifiedKeywords.add(keyword.toLowerCase().trim());
            modifiedKeywords.add(keyword.toLowerCase().trim().replaceAll("\\s", ""));
            modifiedKeywords.add(stem(keyword.toLowerCase()).trim());
            modifiedKeywords.add(stem(keyword.toLowerCase()).trim().replaceAll("\\s", ""));
            modifiedKeywords.add(canonicalize(keyword.toLowerCase().trim()));
            modifiedKeywords.add(canonicalize(keyword.toLowerCase().trim().replaceAll("\\s", "")));
            modifiedKeywords.add(canonicalize(stem(keyword.toLowerCase()).trim()));
            modifiedKeywords.add(canonicalize(stem(keyword.toLowerCase()).trim().replaceAll("\\s", "")));
        }
        for (PositionAnnotation annotation : annotations) {
            String stemmedValue = annotation.getValue();
            String unstemmedValue = annotation.getFeatureVector().getFeature(NominalFeature.class, StemmerAnnotator.UNSTEM).getValue();

            boolean isKeyword = modifiedKeywords.contains(stemmedValue);
            isKeyword |= modifiedKeywords.contains(stemmedValue.toLowerCase());
            isKeyword |= modifiedKeywords.contains(stemmedValue.replaceAll("\\s", ""));
            isKeyword |= modifiedKeywords.contains(stemmedValue.toLowerCase().replaceAll("\\s", ""));
            isKeyword |= modifiedKeywords.contains(unstemmedValue);
            isKeyword |= modifiedKeywords.contains(unstemmedValue.toLowerCase());
            isKeyword |= modifiedKeywords.contains(unstemmedValue.replaceAll("\\s", ""));
            isKeyword |= modifiedKeywords.contains(unstemmedValue.toLowerCase().replaceAll("\\s", ""));
            isKeyword |= modifiedKeywords.contains(canonicalize(stemmedValue));
            isKeyword |= modifiedKeywords.contains(canonicalize(stemmedValue.toLowerCase()));
            isKeyword |= modifiedKeywords.contains(canonicalize(stemmedValue.replaceAll("\\s", "")));
            isKeyword |= modifiedKeywords.contains(canonicalize(stemmedValue.toLowerCase().replaceAll("\\s", "")));
            isKeyword |= modifiedKeywords.contains(canonicalize(unstemmedValue));
            isKeyword |= modifiedKeywords.contains(canonicalize(unstemmedValue.toLowerCase()));
            isKeyword |= modifiedKeywords.contains(canonicalize(unstemmedValue.replaceAll("\\s", "")));
            isKeyword |= modifiedKeywords.contains(canonicalize(unstemmedValue.toLowerCase().replaceAll("\\s", "")));
            NominalFeature isKeywordFeature = new NominalFeature(IS_KEYWORD, String.valueOf(isKeyword));
            annotation.getFeatureVector().add(isKeywordFeature);
            if (isKeyword) {
                marked++;
            }
        }
        return marked;
    }

    /**
     * <p>
     * Re-orders all tokens alphabetically, i.e.
     * <code>the quick brown fox<code> will be transformed to <code>brown fox quick the<code>.
     * </p>
     * 
     * @param string
     * @return
     */
    private static String canonicalize(String string) {
        List<String> result = CollectionHelper.newArrayList();
        for (String s : string.split("\\s")) {
            result.add(s);
        }
        Collections.sort(result);
        return StringUtils.join(result, " ");
    }

    private static List<String> canonicalize(Collection<String> strings) {
        List<String> result = CollectionHelper.newArrayList();
        for (String s : strings) {
            result.add(canonicalize(s));
        }
        return result;
    }

    private String stem(String string) {
        List<String> stems = new ArrayList<String>();
        for (String s : string.split("\\s")) {
            stems.add(stemmer.stem(s));
        }
        return StringUtils.join(stems, " ");
    }

    private Set<String> stem(Set<String> strings) {
        Set<String> stems = CollectionHelper.newHashSet();
        for (String string : strings) {
            stems.add(stem(string));
        }
        return stems;
    }

    @Override
    public void reset() {
        termCorpus.reset();
        keyphraseCorpus.reset();
        cooccurrenceMatrix.reset();
        trainCount = 0;
        trainDocuments.clear();
        classifier = createClassifier();
        super.reset();
    }

    @Override
    public List<Keyphrase> extract(String inputText) {
        TextDocument document = new TextDocument(inputText);
        try {
            corpusGenerationPipeline.process(document);
            candidateGenerationPipeline.process(document);
        } catch (DocumentUnprocessableException e) {
            throw new IllegalStateException();
        }
        List<PositionAnnotation> annotations = document.getFeatureVector().getAll(PositionAnnotation.class, BaseTokenizer.PROVIDED_FEATURE);
        List<Keyphrase> keywords = new ArrayList<Keyphrase>();
        for (PositionAnnotation annotation : annotations) {
            FeatureVector featureVector = annotation.getFeatureVector();
            FeatureVector cleanFv = cleanFeatureVector(featureVector);
            CategoryEntries predictionResult = classifier.classify(cleanFv, model);
            double trueCategory = predictionResult.getProbability("true");
            if (trueCategory != 0) {
                keywords.add(new Keyphrase(annotation.getValue(), trueCategory));
            }
        }
        reRankCooccurrences(keywords);
        // reRankOverlaps(keywords);
        synthetesize(keywords);
        Collections.sort(keywords);
        if (keywords.size() > getKeyphraseCount()) {
            keywords.subList(getKeyphraseCount(), keywords.size()).clear();
        }
        return keywords;
    }

    private int synthetesize(List<Keyphrase> keywords) {
        Collections.sort(keywords);
        Set<String> keyValues = new HashSet<String>();
        for (Keyphrase string : keywords) {
            keyValues.add(string.getValue());
        }
        Map<String, Keyphrase> synthetesized = CollectionHelper.newHashMap();
        int subSize = (int)Math.sqrt(keywords.size());
        for (Keyphrase keyphrase : keywords.subList(0, subSize)) {
            List<Pair<String, Double>> highestPairs = cooccurrenceMatrix.getHighest(keyphrase.getValue(), 5);
            for (Pair<String, Double> pair : highestPairs) {

                String value = pair.getLeft();
                Double weight = pair.getRight() * 1;
                if (keyValues.contains(value)) {
                    continue;
                }
                if (weight < 0.01) {
                    continue;
                }
                if (cooccurrenceMatrix.getCount(keyphrase.getValue(), value) < 2) {
                    continue;
                }
                Keyphrase synthetesizedKeyphrase;
                if (synthetesized.containsKey(value)) {
                    synthetesizedKeyphrase = synthetesized.get(value);
                    synthetesizedKeyphrase.setWeight(synthetesizedKeyphrase.getWeight() + keyphrase.getWeight()
                            * weight);
                } else {
                    synthetesizedKeyphrase = new Keyphrase(value);
                    synthetesizedKeyphrase.setWeight(keyphrase.getWeight() * weight);
                    synthetesized.put(value, synthetesizedKeyphrase);
                }
            }
        }
        keywords.addAll(synthetesized.values());
        return synthetesized.size();
    }

    /**
     * <p>
     * Re-calculate the weight of the list of {@link Keyphrase}s, based on their overlap. Reduce the weights of those
     * candidates which are contained in another candidate. E.g. list contains <code>web</code> with weight
     * <code>0.5</code> and <code>web browser</code> with weight <code>0.7</code>, then weight of <code>web</code> if
     * re-calculated to <code>0.7 - 0.5 = 0.2</code>.
     * </p>
     * 
     * @param keywords
     */
    private void reRankOverlaps(List<Keyphrase> keywords) {
        for (Keyphrase keyphrase1 : keywords) {
            if (keyphrase1.getWeight() > 0) {
                for (Keyphrase keyphrase2 : keywords) {
                    if (keyphrase1.getValue().equals(keyphrase2.getValue())) {
                        continue;
                    }
                    if (keyphrase1.getValue().contains(keyphrase2.getValue())) {
                        keyphrase2.setWeight(keyphrase2.getWeight() - keyphrase1.getWeight());
                    }
                }
            }
        }
    }

    private void reRankCooccurrences(List<Keyphrase> keywords) {

        int subSize = (int)Math.sqrt(keywords.size());
        Collections.sort(keywords);

        for (Keyphrase k1 : keywords.subList(0, subSize)) {
            String value1 = k1.getValue();
            for (Keyphrase k2 : keywords.subList(0, subSize)) {
                String value2 = k2.getValue();
                if (value1.equals(value2)) {
                    continue;
                }
                double condProb = cooccurrenceMatrix.getConditionalProbabilityLaplace(value2, value1);
                // double condProb = cooccurrenceMatrix.getConditionalProbabilityLaplace(value2, value1) * 5;
                // double condProb = cooccurrenceMatrix.getConditionalProbabilityLaplace(value2, value1) * 20;
                double newWeight = k1.getWeight() + k2.getWeight() * condProb;
                k1.setWeight(newWeight);
            }
        }
    }

    @Override
    public String getExtractorName() {
        return getClass().getSimpleName();
    }

    @Override
    public String toString() {
        return getExtractorName();
    }
}
