package ws.palladian.preprocessing.scraping;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.StringReader;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import ws.palladian.helper.html.HTMLHelper;
import de.l3s.boilerpipe.BoilerpipeProcessingException;
import de.l3s.boilerpipe.document.TextDocument;
import de.l3s.boilerpipe.extractors.ArticleExtractor;
import de.l3s.boilerpipe.extractors.ExtractorBase;
import de.l3s.boilerpipe.sax.BoilerpipeSAXInput;

/**
 * Boilerpipe, as described in "Boilerplate Detection using Shallow Text Features"; Kohlschütter, Christian;
 * Fankhauser, Peter; Nejdl, Wolfgang; 2010 and available on http://code.google.com/p/boilerpipe/ and
 * http://www.l3s.de/~kohlschuetter/boilerplate/
 * 
 * @author Ruchit Beri
 * @author Philipp Katz
 * 
 */
public class BoilerpipeContentExtractor extends WebPageContentExtractor {

    /** The logger for this class. */
    private static final Logger LOGGER = Logger.getLogger(BoilerpipeContentExtractor.class);

    private final ExtractorBase extractor;
    private TextDocument textDocument;

    public BoilerpipeContentExtractor() {
        this(ArticleExtractor.INSTANCE);
    }

    public BoilerpipeContentExtractor(ExtractorBase extractor) {
        this.extractor = extractor;
    }

    @Override
    public WebPageContentExtractor setDocument(File file) throws PageContentExtractorException {
        try {
            InputSource inputSource = new InputSource(new FileInputStream(file));
            setDocument(inputSource);
        } catch (FileNotFoundException e) {
            throw new PageContentExtractorException(e);
        }
        return this;
    }

    @Override
    public BoilerpipeContentExtractor setDocument(Document document) throws PageContentExtractorException {
        StringReader stringReader = new StringReader(HTMLHelper.getXmlDump(document));
        InputSource inputSource = new InputSource(stringReader);
        setDocument(inputSource);
        return this;
    }

    // TODO pull up?
    public BoilerpipeContentExtractor setDocument(InputSource inputSource) throws PageContentExtractorException {
        try {
            BoilerpipeSAXInput boilerpipeInput = new BoilerpipeSAXInput(inputSource);
            textDocument = boilerpipeInput.getTextDocument();
            extractor.process(textDocument);
        } catch (SAXException e) {
            throw new PageContentExtractorException(e);
        } catch (BoilerpipeProcessingException e) {
            throw new PageContentExtractorException(e);
        }
        return this;
    }

    @Override
    public Node getResultNode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getResultText() {
        return textDocument.getContent();

    }

    @Override
    public String getResultTitle() {
        return textDocument.getTitle();
    }

    @Override
    public String getExtractorName() {
        return "boilerpipe";
    }

    public static void main(String[] args) throws Exception {
        BoilerpipeContentExtractor bpce = new BoilerpipeContentExtractor();
        bpce.setDocument("http://www.hollyscoop.com/cameron-diaz/52.aspx");
        // bpce.setDocument("http://www.bbc.co.uk/news/world/europe/");
        LOGGER.info("ResultText: " + bpce.getResultText());
        LOGGER.info("ResultTitle: " + bpce.getResultTitle());
    }

}