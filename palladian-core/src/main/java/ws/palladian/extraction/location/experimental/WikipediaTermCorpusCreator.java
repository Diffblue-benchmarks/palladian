package ws.palladian.extraction.location.experimental;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import ws.palladian.extraction.feature.MapTermCorpus;
import ws.palladian.extraction.feature.StemmerAnnotator;
import ws.palladian.extraction.token.Tokenizer;
import ws.palladian.helper.ProcessHelper;
import ws.palladian.helper.collection.CollectionHelper;
import ws.palladian.helper.constants.Language;
import ws.palladian.helper.constants.SizeUnit;
import ws.palladian.helper.nlp.StringHelper;
import ws.palladian.retrieval.wikipedia.MultiStreamBZip2InputStream;
import ws.palladian.retrieval.wikipedia.WikipediaPage;
import ws.palladian.retrieval.wikipedia.WikipediaPageCallback;
import ws.palladian.retrieval.wikipedia.WikipediaPageContentHandler;
import ws.palladian.retrieval.wikipedia.WikipediaUtil;

public class WikipediaTermCorpusCreator {

    /** The logger for this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(WikipediaTermCorpusCreator.class);

    private static final MapTermCorpus corpus = new MapTermCorpus();
    
    private static final StemmerAnnotator stemmer = new StemmerAnnotator(Language.ENGLISH);

    /**
     * @param wikipediaDump Path to the Wikipedia dump file (in .bz2 format).
     * @param outputFile File name and path of the resulting corpus.
     * @param limit Number of pages to read.
     */
    public static void createCorpus(File wikipediaDump, File outputFile, final int limit) {
        if (!wikipediaDump.isFile()) {
            throw new IllegalArgumentException(wikipediaDump + " is not a file or could not be accessed.");
        }
        Validate.notNull(wikipediaDump, "wikipediaDump must not be null");
        Validate.notNull(outputFile, "outputFile must not be null");
        Validate.isTrue(limit > 0, "limit must be greater zero");
        corpus.clear();
        try {
            SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
            SAXParser parser = saxParserFactory.newSAXParser();
            InputStream inputStream = new MultiStreamBZip2InputStream(new BufferedInputStream(new FileInputStream(
                    wikipediaDump)));
            final int[] counter = new int[] {0};
            parser.parse(inputStream, new WikipediaPageContentHandler(new WikipediaPageCallback() {
                @Override
                public void callback(WikipediaPage page) {
                    if (page.getNamespaceId() != WikipediaPage.MAIN_NAMESPACE) {
                        return;
                    }
                    if (counter[0]++ == limit) {
                        throw new StopException();
                    }
                    if (ProcessHelper.getFreeMemory() < SizeUnit.MEGABYTES.toBytes(128)) {
                        LOGGER.info("Memory nearly exhausted, stopping. Make sure to assign lots of heap memory before running!");
                        throw new StopException();
                    }
                    String pageText = WikipediaUtil.stripMediaWikiMarkup(page.getText());
                    pageText = StringHelper.normalizeQuotes(pageText);
                    pageText = WikipediaUtil.extractSentences(pageText);
                    addCounts(pageText);
                }
            }));
        } catch (StopException e) {
            // finished.
        } catch (FileNotFoundException e) {
            throw new IllegalStateException(e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        } catch (SAXException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        try {
            corpus.save(outputFile);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void addCounts(String pageText) {
        List<String> tokens = Tokenizer.tokenize(pageText);
        Set<String> tokenSet = CollectionHelper.newHashSet();
        for (String token : tokens) {
            String stemmed = stemmer.stem(token.toLowerCase());
            tokenSet.add(new String(stemmed));
        }
        corpus.addTermsFromDocument(tokenSet);
    }

//    public static void clean(File fileName, File outputFileName, final int minOccurCount) {
//        final Writer[] writer = new Writer[1];
//        try {
//            final ProgressMonitor monitor = new ProgressMonitor(FileHelper.getNumberOfLines(fileName));
//            writer[0] = new BufferedWriter(new FileWriter(outputFileName));
//            FileHelper.performActionOnEveryLine(fileName, new LineAction() {
//
//                @Override
//                public void performAction(String line, int lineNumber) {
//                    monitor.incrementAndPrintProgress();
//                    String[] split = line.split("\\t");
//                    if (split.length != 2) {
//                        return;
//                    }
//                    if (Integer.valueOf(split[1]) >= minOccurCount) {
//                        try {
//                            writer[0].write(line);
//                            writer[0].write("\n");
//                        } catch (IOException e) {
//                            throw new IllegalStateException(e);
//                        }
//                    }
//                }
//            });
//        } catch (IOException e) {
//            throw new IllegalStateException(e);
//        } finally {
//            FileHelper.close(writer);
//        }
//    }

    public static void main(String[] args) {
        File wikipediaDump = new File("/Volumes/iMac HD/temp/enwiki-20130503-pages-articles.xml.bz2");
        File outputPath = new File("/Users/pk/Desktop/wikipediaTermCorpusStemmedFull.gz");
        createCorpus(wikipediaDump, outputPath, Integer.MAX_VALUE);
    }

}